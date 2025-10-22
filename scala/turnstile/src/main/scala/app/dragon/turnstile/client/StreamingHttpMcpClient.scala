package app.dragon.turnstile.client

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.scaladsl.{Framing, Keep, Sink}
import org.apache.pekko.util.ByteString
import org.slf4j.{Logger, LoggerFactory}
import io.modelcontextprotocol.spec.HttpHeaders

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * HTTP Streaming MCP Client using Server-Sent Events (SSE).
 *
 * This client demonstrates the full workflow for connecting to an HTTP streaming MCP server:
 * 1. Initialize connection via POST to get session ID
 * 2. Establish SSE stream via GET with session ID
 * 3. Send requests via POST with session ID
 * 4. Receive responses via SSE stream
 * 5. Close session via DELETE
 *
 * The streaming protocol provides:
 * - Long-lived bidirectional communication
 * - Server-initiated messages (notifications, progress)
 * - Message replay on reconnection
 * - Session-based state management
 *
 * Usage:
 * {{{
 * sbt "runMain app.dragon.turnstile.client.StreamingHttpMcpClient http://localhost:8082/mcp"
 * }}}
 */
object StreamingHttpMcpClient {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private val jsonMapper = new ObjectMapper()

  // Minimal configuration without clustering to avoid port conflicts
  private val clientConfig = ConfigFactory.parseString(
    """
      |pekko {
      |  loglevel = INFO
      |  actor {
      |    provider = local
      |  }
      |}
      |""".stripMargin
  )

  // Static actor system for Pekko HTTP (required for SSE support)
  private implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "streaming-mcp-client", clientConfig)
  private implicit val ec: ExecutionContext = system.executionContext

  def main(args: Array[String]): Unit = {
    try {
      val serverUrl = if (args.nonEmpty) args(0) else "http://localhost:8082/mcp"
      logger.info(s"Starting Streaming HTTP MCP Client for: $serverUrl")

      // Run the client workflow
      val result = runClient(serverUrl)
      Await.result(result, 60.seconds)

      logger.info("\n=== Streaming HTTP MCP Client completed successfully ===")
    } catch {
      case ex: Exception =>
        logger.error("Streaming HTTP MCP Client failed", ex)
        System.exit(1)
    } finally {
      system.terminate()
      Await.result(system.whenTerminated, 10.seconds)
    }
  }

  private def runClient(serverUrl: String): Future[Unit] = {
    for {
      // Step 1: Initialize and get session ID
      sessionId <- initializeSession(serverUrl)
      _ = logger.info(s"\n=== Session initialized: $sessionId ===")

      // Step 2: Establish SSE connection
      sseConnection <- establishSseStream(serverUrl, sessionId)
      _ = logger.info("\n=== SSE stream established ===")

      /*
      // Step 3: List tools
      _ <- listTools(serverUrl, sessionId)
      _ = Thread.sleep(10000)  // Wait for SSE response
*/

      // Step 4: Test echo tool
      _ <- testEchoTool(serverUrl, sessionId)
      _ = Thread.sleep(20000)  // Wait for SSE responses

      /*
      // Step 5: Test system_info tool
      _ <- testSystemInfoTool(serverUrl, sessionId)
      _ = Thread.sleep(1000)  // Wait for SSE response

      // Step 6: Wait a bit for any pending SSE messages
      _ = Thread.sleep(10000)

      // Step 7: Close session
      _ <- closeSession(serverUrl, sessionId)
      _ = logger.info("\n=== Session closed ===")

       */

      // Step 8: Terminate SSE connection
      _ = sseConnection._1.complete()
      _ = logger.info("SSE connection terminated")

    } yield ()
  }

  /**
   * Initialize session and get session ID
   */
  private def initializeSession(serverUrl: String): Future[String] = {
    logger.info("\n=== Initializing Connection ===")

    val initRequest = Map(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "initialize",
      "params" -> Map(
        "protocolVersion" -> "2024-11-05",
        "capabilities" -> Map[String, Any]().asJava,
        "clientInfo" -> Map(
          "name" -> "turnstile-streaming-test-client",
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
      response.status match {
        case StatusCodes.OK =>
          // Log all response headers for debugging
          logger.debug("Response headers:")
          response.headers.foreach { header =>
            logger.debug(s"  ${header.name()}: ${header.value()}")
          }

          // Extract session ID from header (case-insensitive)
          val sessionIdOpt = response.headers.find(_.name().equalsIgnoreCase("mcp-session-id")).map(_.value())
          sessionIdOpt match {
            case Some(sessionId) if sessionId.nonEmpty =>
              logger.info(s"Extracted session ID: $sessionId")
              // Parse response body
              Unmarshal(response.entity).to[String].map { body =>
                val jsonResponse = jsonMapper.readTree(body)
                val result = jsonResponse.get("result")

                logger.info(s"Protocol Version: ${result.get("protocolVersion").asText()}")
                val serverInfo = result.get("serverInfo")
                logger.info(s"Server: ${serverInfo.get("name").asText()} v${serverInfo.get("version").asText()}")
                logger.info(s"Session ID: $sessionId")

                sessionId
              }
            case _ =>
              logger.error("No mcp-session-id header found in response or session ID is empty")
              logger.error(s"Available headers: ${response.headers.map(h => s"${h.name()}=${h.value()}").mkString(", ")}")
              Future.failed(new RuntimeException("No session ID in response or session ID is empty"))
          }
        case _ =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            Future.failed(new RuntimeException(s"Initialize failed: ${response.status} - $body"))
          }
      }
    }
  }

  /**
   * Establish SSE stream for receiving server messages
   */
  private def establishSseStream(serverUrl: String, sessionId: String): Future[(org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[Unit], Future[Unit])] = {
    logger.info("\n=== Establishing SSE Stream ===")

    val httpRequest = HttpRequest(
      method = HttpMethods.GET,
      uri = serverUrl,
      headers = List(
        RawHeader("Accept", "text/event-stream"),
        RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)
      )
    )

    Http().singleRequest(httpRequest).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          logger.info("SSE stream connected")

          // Create a control queue for managing the stream
          val (queue, done) = org.apache.pekko.stream.scaladsl.Source.queue[Unit](1, org.apache.pekko.stream.OverflowStrategy.dropHead)
            .toMat(Sink.ignore)(Keep.both)
            .run()

          // Process SSE events - parse line-by-line
          val eventsFuture = response.entity.dataBytes
            .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
            .map(_.utf8String)
            .runForeach { line =>
              if (line.startsWith("data: ")) {
                val data = line.substring(6)
                logger.info(s"SSE data: ${data.take(200)}")

                // Parse and display the message
                try {
                  val jsonData = jsonMapper.readTree(data)
                  if (jsonData.has("result")) {
                    val result = jsonData.get("result")
                    logger.info(s"  → Result: ${result.toString.take(200)}")
                  } else if (jsonData.has("error")) {
                    logger.error(s"  → Error: ${jsonData.get("error")}")
                  }
                } catch {
                  case ex: Exception =>
                    logger.debug(s"Could not parse event data as JSON: ${ex.getMessage}")
                }
              }
            }
            .map(_ => ())

          Future.successful((queue, eventsFuture))

        case _ =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            Future.failed(new RuntimeException(s"SSE connection failed: ${response.status} - $body"))
          }
      }
    }
  }

  /**
   * List available tools
   */
  private def listTools(serverUrl: String, sessionId: String): Future[Unit] = {
    logger.info("\n=== Listing Available Tools ===")

    val listRequest: java.util.Map[String, Any] = Map(
      "jsonrpc" -> "2.0",
      "id" -> 2,
      "method" -> "tools/list",
      "params" -> Map[String, Any]().asJava
    ).asJava.asInstanceOf[java.util.Map[String, Any]]

    sendRequest(serverUrl, sessionId, listRequest).map { response =>
      // Response will come via SSE for streaming transport
      if (response.has("result")) {
        val result = response.get("result")
        val tools = result.get("tools")
        logger.info(s"Found ${tools.size()} tools:")
        tools.elements().asScala.foreach { tool =>
          logger.info(s"  - ${tool.get("name").asText()}: ${tool.get("description").asText()}")
        }
      } else {
        logger.info("Request sent, waiting for SSE response...")
      }
    }
  }

  /**
   * Test the echo tool
   */
  private def testEchoTool(serverUrl: String, sessionId: String): Future[Unit] = {
    logger.info("\n=== Testing Echo Tool ===")

    val testMessages = List("Hello, Streaming HTTP!", "Testing SSE transport", "Pekko HTTP + MCP!")

    val futures = testMessages.zipWithIndex.map { case (message, index) =>
      val callRequest: java.util.Map[String, Any] = Map(
        "jsonrpc" -> "2.0",
        "id" -> (10 + index),
        "method" -> "tools/call",
        "params" -> Map(
          "name" -> "echo",
          "arguments" -> Map(
            "message" -> message
          ).asJava
        ).asJava
      ).asJava.asInstanceOf[java.util.Map[String, Any]]

      sendRequest(serverUrl, sessionId, callRequest).map { response =>
        // Response will come via SSE for streaming transport
        if (response.has("result")) {
          val result = response.get("result")
          val content = result.get("content")

          content.elements().asScala.foreach { item =>
            if (item.get("type").asText() == "text") {
              logger.info(s"  Input: '$message'")
              logger.info(s"  Output: '${item.get("text").asText()}'")
            }
          }
        } else {
          logger.info(s"Echo request sent for: '$message', waiting for SSE response...")
        }
      }.recover {
        case ex: Exception =>
          logger.error(s"Failed to call echo tool with message '$message'", ex)
      }
    }

    Future.sequence(futures).map(_ => ())
  }

  /**
   * Test the system_info tool
   */
  private def testSystemInfoTool(serverUrl: String, sessionId: String): Future[Unit] = {
    logger.info("\n=== Testing System Info Tool ===")

    val callRequest: java.util.Map[String, Any] = Map(
      "jsonrpc" -> "2.0",
      "id" -> 20,
      "method" -> "tools/call",
      "params" -> Map(
        "name" -> "system_info",
        "arguments" -> Map[String, Any]().asJava
      ).asJava
    ).asJava.asInstanceOf[java.util.Map[String, Any]]

    sendRequest(serverUrl, sessionId, callRequest).map { response =>
      // Response will come via SSE for streaming transport
      if (response.has("result")) {
        val result = response.get("result")
        val content = result.get("content")

        content.elements().asScala.foreach { item =>
          if (item.get("type").asText() == "text") {
            logger.info(s"System Information:\n${item.get("text").asText()}")
          }
        }
      } else {
        logger.info("System info request sent, waiting for SSE response...")
      }
    }.recover {
      case ex: Exception =>
        logger.error("Failed to call system_info tool", ex)
    }
  }

  /**
   * Send a request and get response (for non-streaming responses, we get via SSE)
   */
  private def sendRequest(serverUrl: String, sessionId: String, requestBody: java.util.Map[String, Any]): Future[JsonNode] = {
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
          // Response will come via SSE, return empty for now
          Future.successful(jsonMapper.createObjectNode())

        case StatusCodes.OK =>
          // Direct response (for some methods)
          Unmarshal(response.entity).to[String].map { body =>
            jsonMapper.readTree(body)
          }

        case _ =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            Future.failed(new RuntimeException(s"Request failed: ${response.status} - $body"))
          }
      }
    }
  }

  /**
   * Close the session
   */
  private def closeSession(serverUrl: String, sessionId: String): Future[Unit] = {
    logger.info("\n=== Closing Session ===")

    val httpRequest = HttpRequest(
      method = HttpMethods.DELETE,
      uri = serverUrl,
      headers = List(
        RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)
      )
    )

    Http().singleRequest(httpRequest).flatMap { response =>
      response.status match {
        case StatusCodes.OK =>
          logger.info(s"Session $sessionId closed successfully")
          Future.successful(())

        case _ =>
          Unmarshal(response.entity).to[String].flatMap { body =>
            Future.failed(new RuntimeException(s"Close session failed: ${response.status} - $body"))
          }
      }
    }
  }
}
