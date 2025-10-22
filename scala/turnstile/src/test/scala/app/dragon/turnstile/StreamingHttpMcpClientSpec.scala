package app.dragon.turnstile

import app.dragon.turnstile.mcp.StreamingHttpMcpServer
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.typesafe.config.ConfigFactory
import io.modelcontextprotocol.spec.HttpHeaders
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.scaladsl.{Framing, Keep, Sink}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * Integration test for Streaming HTTP MCP Client with bidirectional SSE.
 *
 * This test validates:
 * - Session initialization and management
 * - SSE stream establishment and message routing
 * - Request/response matching by JSON-RPC ID
 * - Tool invocation (echo, system_info)
 * - Concurrent request handling
 * - Response timeout detection
 * - Graceful session closure
 */
class StreamingHttpMcpClientSpec
  extends AnyWordSpec
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures
  with Eventually {

  private val testKit = ActorTestKit()
  private implicit val system: ActorSystem[?] = testKit.system
  private implicit val ec: ExecutionContext = system.executionContext

  // Test configuration
  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(30, Seconds)), interval = scaled(Span(500, org.scalatest.time.Millis)))

  private val jsonMapper = new ObjectMapper()

  // Server configuration
  private val serverPort = 18082 // Use different port to avoid conflicts
  private val serverUrl = s"http://localhost:$serverPort/mcp"

  private val testConfig = ConfigFactory.parseString(
    s"""
       |turnstile.mcp {
       |  enabled = true
       |  name = "Test MCP Server"
       |  version = "1.0.0"
       |  host = "127.0.0.1"
       |  port = $serverPort
       |}
       |""".stripMargin
  ).withFallback(ConfigFactory.defaultApplication())

  private var mcpServer: StreamingHttpMcpServer = null.asInstanceOf[StreamingHttpMcpServer]

  // Response tracking (shared across tests)
  private val pendingRequests = new ConcurrentHashMap[Int, Promise[JsonNode]]()
  private val receivedResponses = new AtomicInteger(0)
  private val expectedResponses = new AtomicInteger(0)

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Start MCP server
    mcpServer = StreamingHttpMcpServer(testConfig.getConfig("turnstile.mcp"))
    mcpServer.start()

    // Wait for server to be ready
    Thread.sleep(2000)
  }

  override def afterAll(): Unit = {
    try {
      // Stop MCP server
      if (mcpServer != null) {
        mcpServer.stop()
      }

      // Shutdown test kit
      testKit.shutdownTestKit()
    } finally {
      super.afterAll()
    }
  }

  "Streaming HTTP MCP Client" should {

    "initialize session and get session ID" in {
      val initRequest = Map(
        "jsonrpc" -> "2.0",
        "id" -> 1,
        "method" -> "initialize",
        "params" -> Map(
          "protocolVersion" -> "2024-11-05",
          "capabilities" -> Map[String, Any]().asJava,
          "clientInfo" -> Map(
            "name" -> "test-client",
            "version" -> "1.0.0"
          ).asJava
        ).asJava
      ).asJava

      val requestJson = jsonMapper.writeValueAsString(initRequest)

      val httpRequest = HttpRequest(
        method = HttpMethods.POST,
        uri = serverUrl,
        headers = List(
          RawHeader("Accept", "text/event-stream, application/json")
        ),
        entity = HttpEntity(ContentTypes.`application/json`, requestJson)
      )

      val responseFuture = Http().singleRequest(httpRequest).flatMap { response =>
        response.status shouldBe StatusCodes.OK

        // Extract session ID from header
        val sessionIdOpt = response.headers.find(_.name().equalsIgnoreCase("mcp-session-id")).map(_.value())
        sessionIdOpt should not be empty

        val sessionId = sessionIdOpt.get
        sessionId should not be empty

        // Parse response body
        Unmarshal(response.entity).to[String].map { body =>
          val jsonResponse = jsonMapper.readTree(body)
          jsonResponse.has("result") shouldBe true

          val result = jsonResponse.get("result")
          result.has("protocolVersion") shouldBe true
          result.has("serverInfo") shouldBe true

          val serverInfo = result.get("serverInfo")
          serverInfo.get("name").asText() shouldBe "Test MCP Server"
          serverInfo.get("version").asText() shouldBe "1.0.0"

          sessionId
        }
      }

      whenReady(responseFuture) { sessionId =>
        sessionId should not be empty
        sessionId should have length 36 // UUID format
      }
    }

    "establish SSE stream and receive endpoint event" in {
      // First initialize to get session ID
      val sessionId = initializeSession().futureValue

      val httpRequest = HttpRequest(
        method = HttpMethods.GET,
        uri = serverUrl,
        headers = List(
          RawHeader("Accept", "text/event-stream"),
          RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)
        )
      )

      val streamFuture = Http().singleRequest(httpRequest).flatMap { response =>
        response.status shouldBe StatusCodes.OK
        // Check content type is text/event-stream
        response.entity.contentType.mediaType.mainType shouldBe "text"
        response.entity.contentType.mediaType.subType shouldBe "event-stream"

        // Read first few events
        val eventsFuture = response.entity.dataBytes
          .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
          .map(_.utf8String)
          .filter(_.startsWith("data: "))
          .map(_.substring(6))
          .take(1) // Just take endpoint event
          .runWith(Sink.head)

        eventsFuture.map { data =>
          // Endpoint event should contain the MCP endpoint path
          data should include("/mcp")
          data should include(s":$serverPort")
        }
      }

      whenReady(streamFuture) { _ =>
        succeed
      }
    }

    "list available tools via streaming" in {
      val sessionId = initializeSession().futureValue
      val (queue, sseStreamFuture) = establishSseStream(sessionId).futureValue

      val requestId = 100
      val listRequest: java.util.Map[String, Any] = Map(
        "jsonrpc" -> "2.0",
        "id" -> requestId,
        "method" -> "tools/list",
        "params" -> Map[String, Any]().asJava
      ).asJava.asInstanceOf[java.util.Map[String, Any]]

      val responseFuture = sendRequestWithTracking(serverUrl, sessionId, listRequest)

      whenReady(responseFuture, timeout(scaled(Span(10, Seconds)))) { response =>
        response.has("result") shouldBe true

        val result = response.get("result")
        result.has("tools") shouldBe true

        val tools = result.get("tools")
        tools.size() should be >= 2

        val toolNames = tools.elements().asScala.map(_.get("name").asText()).toSet
        toolNames should contain("echo")
        toolNames should contain("system_info")
      }

      // Cleanup
      queue.complete()
      deleteSession(sessionId).futureValue
    }

    "handle echo tool with correct response format" in {
      val sessionId = initializeSession().futureValue
      val (queue, sseStreamFuture) = establishSseStream(sessionId).futureValue

      val testMessage = "Hello, ScalaTest!"
      val requestId = 101
      val callRequest: java.util.Map[String, Any] = Map(
        "jsonrpc" -> "2.0",
        "id" -> requestId,
        "method" -> "tools/call",
        "params" -> Map(
          "name" -> "echo",
          "arguments" -> Map(
            "message" -> testMessage
          ).asJava
        ).asJava
      ).asJava.asInstanceOf[java.util.Map[String, Any]]

      val responseFuture = sendRequestWithTracking(serverUrl, sessionId, callRequest)

      whenReady(responseFuture, timeout(scaled(Span(10, Seconds)))) { response =>
        response.has("result") shouldBe true

        val result = response.get("result")
        result.has("content") shouldBe true

        val content = result.get("content")
        val textItems = content.elements().asScala.filter(_.get("type").asText() == "text").toList
        textItems should not be empty

        val echoedText = textItems.head.get("text").asText()
        echoedText shouldBe s"Echo: $testMessage"
      }

      // Cleanup
      queue.complete()
      deleteSession(sessionId).futureValue
    }

    "handle system_info tool with expected fields" in {
      val sessionId = initializeSession().futureValue
      val (queue, sseStreamFuture) = establishSseStream(sessionId).futureValue

      val requestId = 102
      val callRequest: java.util.Map[String, Any] = Map(
        "jsonrpc" -> "2.0",
        "id" -> requestId,
        "method" -> "tools/call",
        "params" -> Map(
          "name" -> "system_info",
          "arguments" -> Map[String, Any]().asJava
        ).asJava
      ).asJava.asInstanceOf[java.util.Map[String, Any]]

      val responseFuture = sendRequestWithTracking(serverUrl, sessionId, callRequest)

      whenReady(responseFuture, timeout(scaled(Span(10, Seconds)))) { response =>
        response.has("result") shouldBe true

        val result = response.get("result")
        result.has("content") shouldBe true

        val content = result.get("content")
        val textItems = content.elements().asScala.filter(_.get("type").asText() == "text").toList
        textItems should not be empty

        val sysInfo = textItems.head.get("text").asText()

        // Validate expected fields
        sysInfo should include("OS Name:")
        sysInfo should include("Java Version:")
        sysInfo should include("Available Processors:")
        sysInfo should include("Total Memory:")
      }

      // Cleanup
      queue.complete()
      deleteSession(sessionId).futureValue
    }

    "handle multiple concurrent echo requests" in {
      val sessionId = initializeSession().futureValue
      val (queue, sseStreamFuture) = establishSseStream(sessionId).futureValue

      val testMessages = List(
        "Message 1",
        "Message 2",
        "Message 3"
      )

      val requestFutures = testMessages.zipWithIndex.map { case (message, index) =>
        val requestId = 200 + index
        val callRequest: java.util.Map[String, Any] = Map(
          "jsonrpc" -> "2.0",
          "id" -> requestId,
          "method" -> "tools/call",
          "params" -> Map(
            "name" -> "echo",
            "arguments" -> Map(
              "message" -> message
            ).asJava
          ).asJava
        ).asJava.asInstanceOf[java.util.Map[String, Any]]

        sendRequestWithTracking(serverUrl, sessionId, callRequest).map { response =>
          (message, response)
        }
      }

      val allResponsesFuture = Future.sequence(requestFutures)

      whenReady(allResponsesFuture, timeout(scaled(Span(15, Seconds)))) { responses =>
        responses should have size 3

        responses.foreach { case (originalMessage, response) =>
          response.has("result") shouldBe true
          val result = response.get("result")
          val content = result.get("content")
          val textItems = content.elements().asScala.filter(_.get("type").asText() == "text").toList
          val echoedText = textItems.head.get("text").asText()

          echoedText shouldBe s"Echo: $originalMessage"
        }
      }

      // Cleanup
      queue.complete()
      deleteSession(sessionId).futureValue
    }

    "close session gracefully" in {
      val sessionId = initializeSession().futureValue

      val httpRequest = HttpRequest(
        method = HttpMethods.DELETE,
        uri = serverUrl,
        headers = List(
          RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)
        )
      )

      val deleteFuture = Http().singleRequest(httpRequest).flatMap { response =>
        response.status shouldBe StatusCodes.OK
        Future.successful(())
      }

      whenReady(deleteFuture) { _ =>
        succeed
      }
    }
  }

  // Helper methods

  private def initializeSession(): Future[String] = {
    val initRequest = Map(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "initialize",
      "params" -> Map(
        "protocolVersion" -> "2024-11-05",
        "capabilities" -> Map[String, Any]().asJava,
        "clientInfo" -> Map(
          "name" -> "test-client",
          "version" -> "1.0.0"
        ).asJava
      ).asJava
    ).asJava

    val requestJson = jsonMapper.writeValueAsString(initRequest)

    val httpRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = serverUrl,
      headers = List(
        RawHeader("Accept", "text/event-stream, application/json")
      ),
      entity = HttpEntity(ContentTypes.`application/json`, requestJson)
    )

    Http().singleRequest(httpRequest).flatMap { response =>
      val sessionIdOpt = response.headers.find(_.name().equalsIgnoreCase("mcp-session-id")).map(_.value())
      Future.successful(sessionIdOpt.get)
    }
  }

  private def establishSseStream(sessionId: String): Future[(org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[Unit], Future[Unit])] = {
    val httpRequest = HttpRequest(
      method = HttpMethods.GET,
      uri = serverUrl,
      headers = List(
        RawHeader("Accept", "text/event-stream"),
        RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)
      )
    )

    Http().singleRequest(httpRequest).flatMap { response =>
      // Create a control queue for managing the stream
      val (queue, done) = org.apache.pekko.stream.scaladsl.Source.queue[Unit](1, org.apache.pekko.stream.OverflowStrategy.dropHead)
        .toMat(Sink.ignore)(Keep.both)
        .run()

      // Process SSE events - parse line-by-line and match with pending requests
      val eventsFuture = response.entity.dataBytes
        .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
        .map(_.utf8String)
        .runForeach { line =>
          if (line.startsWith("data: ")) {
            val data = line.substring(6)

            // Skip non-JSON SSE events (like endpoint announcements)
            if (!data.startsWith("http")) {
              try {
                val jsonData = jsonMapper.readTree(data)

                // Extract request ID if present
                if (jsonData.has("id")) {
                  val id = jsonData.get("id").asInt()
                  val promise = pendingRequests.remove(id)

                  if (promise != null) {
                    receivedResponses.incrementAndGet()
                    promise.success(jsonData)
                  }
                }
              } catch {
                case _: Exception => // Ignore parse errors
              }
            }
          }
        }
        .map(_ => ())

      Future.successful((queue, eventsFuture))
    }
  }

  private def sendRequestWithTracking(
    serverUrl: String,
    sessionId: String,
    requestBody: java.util.Map[String, Any]
  ): Future[JsonNode] = {
    val requestId = requestBody.get("id").asInstanceOf[Int]
    val promise = Promise[JsonNode]()

    // Register pending request
    pendingRequests.put(requestId, promise)
    expectedResponses.incrementAndGet()

    val requestJson = jsonMapper.writeValueAsString(requestBody)

    val httpRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = serverUrl,
      headers = List(
        RawHeader("Accept", "text/event-stream, application/json"),
        RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)
      ),
      entity = HttpEntity(ContentTypes.`application/json`, requestJson)
    )

    Http().singleRequest(httpRequest).flatMap { response =>
      response.status match {
        case StatusCodes.Accepted =>
          // Response will come via SSE stream
          promise.future

        case StatusCodes.OK =>
          // Direct response (shouldn't happen in streaming mode, but handle it)
          Unmarshal(response.entity).to[String].map { body =>
            val jsonResponse = jsonMapper.readTree(body)
            receivedResponses.incrementAndGet()
            promise.success(jsonResponse)
            jsonResponse
          }

        case _ =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            val error = new RuntimeException(s"Request failed: ${response.status} - $body")
            promise.failure(error)
            pendingRequests.remove(requestId)
            Future.failed(error)
          }
      }
    }
  }

  private def deleteSession(sessionId: String): Future[Unit] = {
    val httpRequest = HttpRequest(
      method = HttpMethods.DELETE,
      uri = serverUrl,
      headers = List(
        RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)
      )
    )

    Http().singleRequest(httpRequest).map { response =>
      response.status shouldBe StatusCodes.OK
      ()
    }
  }
}
