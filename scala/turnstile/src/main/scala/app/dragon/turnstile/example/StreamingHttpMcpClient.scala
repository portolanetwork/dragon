package app.dragon.turnstile.example

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
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP Streaming MCP Client Test Case using Server-Sent Events (SSE).
 *
 * This test case validates the full workflow for bidirectional streaming MCP communication:
 * 1. Initialize connection via POST to get session ID
 * 2. Establish SSE stream via GET with session ID
 * 3. Send requests via POST with session ID
 * 4. Verify all responses are received via SSE stream
 * 5. Close session via DELETE
 *
 * Test Validation Features:
 * - Tracks all pending requests by ID
 * - Verifies each response is received within timeout
 * - Validates response content matches expected format
 * - Ensures bidirectional streaming works correctly
 * - Reports test results with pass/fail status
 *
 * Usage:
 * {{{
 * sbt "runMain app.dragon.turnstile.client.StreamingHttpMcpClient http://localhost:8082/mcp"
 * }}}
 */
object StreamingHttpMcpClient {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private val jsonMapper = new ObjectMapper()

  // Test result tracking
  case class TestResult(
    testName: String,
    requestId: Int,
    success: Boolean,
    message: String,
    responseTimeMs: Long
  )

  // Response tracking
  private val pendingRequests = new ConcurrentHashMap[Int, Promise[JsonNode]]()
  private val testResults = new java.util.concurrent.CopyOnWriteArrayList[TestResult]()
  private val receivedResponses = new AtomicInteger(0)
  private val expectedResponses = new AtomicInteger(0)

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
      logger.info(s"=== Starting Streaming HTTP MCP Client Test Case ===")
      logger.info(s"Server URL: $serverUrl")
      logger.info(s"Test Objective: Verify bidirectional streaming with all responses received\n")

      // Run the test client workflow
      val result = runClient(serverUrl)
      Await.result(result, 60.seconds)

      // Print test results
      printTestResults()

      // Exit with appropriate code
      val allPassed = testResults.asScala.forall(_.success)
      if (allPassed) {
        logger.info("\n✓ ALL TESTS PASSED")
        System.exit(0)
      } else {
        logger.error("\n✗ SOME TESTS FAILED")
        System.exit(1)
      }
    } catch {
      case ex: Exception =>
        logger.error("Test execution failed with exception", ex)
        printTestResults()
        System.exit(1)
    } finally {
      system.terminate()
      Await.result(system.whenTerminated, 10.seconds)
    }
  }

  private def printTestResults(): Unit = {
    logger.info("\n" + "="*80)
    logger.info("TEST RESULTS SUMMARY")
    logger.info("="*80)

    val results = testResults.asScala.toList
    val passed = results.count(_.success)
    val failed = results.count(!_.success)
    val totalExpected = expectedResponses.get()
    val totalReceived = receivedResponses.get()

    results.foreach { result =>
      val status = if (result.success) "✓ PASS" else "✗ FAIL"
      val timing = f"${result.responseTimeMs}%4d ms"
      logger.info(f"[$status] [ID:${result.requestId}%2d] [$timing] ${result.testName}")
      if (!result.success) {
        logger.info(f"         Reason: ${result.message}")
      }
    }

    logger.info("-"*80)
    logger.info(f"Total Tests: ${results.size}  Passed: $passed  Failed: $failed")
    logger.info(f"Expected Responses: $totalExpected  Received: $totalReceived")
    logger.info("="*80)
  }

  private def runClient(serverUrl: String): Future[Unit] = {
    for {
      // Test 1: Initialize and get session ID
      sessionId <- initializeSession(serverUrl)
      _ = logger.info(s"✓ Session initialized: $sessionId\n")

      // Test 2: Establish SSE connection
      sseConnection <- establishSseStream(serverUrl, sessionId)
      _ = logger.info("✓ SSE stream established\n")

      // Test 3: List tools
      _ <- listTools(serverUrl, sessionId)

      // Test 4: Test echo tool with multiple messages
      _ <- testEchoTool(serverUrl, sessionId)

      // Test 5: Test system_info tool
      _ <- testSystemInfoTool(serverUrl, sessionId)

      // Wait for all pending responses with timeout
      _ <- waitForAllResponses(timeout = 10.seconds)

      // Test 6: Close session
      _ <- closeSession(serverUrl, sessionId)
      _ = logger.info("✓ Session closed\n")

      // Terminate SSE connection
      _ = sseConnection._1.complete()
      _ = logger.info("✓ SSE connection terminated\n")

    } yield ()
  }

  /**
   * Wait for all pending responses to be received
   */
  private def waitForAllResponses(timeout: FiniteDuration): Future[Unit] = {
    val expected = expectedResponses.get()
    if (expected == 0) {
      return Future.successful(())
    }

    logger.info(s"Waiting for $expected responses (timeout: ${timeout.toSeconds}s)...")

    val allFutures = pendingRequests.values().asScala.map(_.future).toList
    val allResponsesFuture = Future.sequence(allFutures).map(_ => ())

    // Race between timeout and all responses
    Future.firstCompletedOf(List(
      allResponsesFuture,
      Future {
        Thread.sleep(timeout.toMillis)
        ()
      }
    )).map { _ =>
      val received = receivedResponses.get()
      val pending = expected - received

      if (pending > 0) {
        logger.warn(s"⚠ Timeout: $pending responses still pending after ${timeout.toSeconds}s")

        // Mark pending requests as failed
        pendingRequests.asScala.foreach { case (id, promise) =>
          if (!promise.isCompleted) {
            testResults.add(TestResult(
              testName = s"Request ID $id",
              requestId = id,
              success = false,
              message = "Response not received within timeout",
              responseTimeMs = timeout.toMillis
            ))
            promise.failure(new java.util.concurrent.TimeoutException(s"Response not received for request $id"))
          }
        }
      } else {
        logger.info(s"✓ All $received responses received successfully")
      }
    }
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
    logger.info("=== TEST: Establishing SSE Stream ===")

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

          // Process SSE events - parse line-by-line and match with pending requests
          val eventsFuture = response.entity.dataBytes
            .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
            .map(_.utf8String)
            .runForeach { line =>
              if (line.startsWith("data: ")) {
                val data = line.substring(6)

                // Skip non-JSON SSE events (like endpoint announcements)
                if (!data.startsWith("http")) {
                  logger.debug(s"Received SSE data: ${data.take(100)}...")

                  // Parse and route the response to the appropriate promise
                  try {
                    val jsonData = jsonMapper.readTree(data)

                    // Extract request ID if present
                    if (jsonData.has("id")) {
                      val id = jsonData.get("id").asInt()
                      val promise = pendingRequests.remove(id)

                      if (promise != null) {
                        val received = receivedResponses.incrementAndGet()
                        logger.info(s"✓ Received response for request ID $id (${received}/${expectedResponses.get()})")
                        promise.success(jsonData)
                      } else {
                        logger.debug(s"Received response for ID $id but no pending request found")
                      }
                    }

                    // Log result/error for debugging
                    if (jsonData.has("result")) {
                      val result = jsonData.get("result")
                      logger.debug(s"  Result: ${result.toString.take(100)}")
                    } else if (jsonData.has("error")) {
                      val error = jsonData.get("error")
                      logger.error(s"  Error: $error")
                    }
                  } catch {
                    case ex: Exception =>
                      logger.debug(s"Could not parse SSE data as JSON: ${ex.getMessage}")
                  }
                }
              } else if (line.startsWith("event: ")) {
                val eventType = line.substring(7)
                logger.debug(s"SSE event type: $eventType")
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
    logger.info("=== TEST: Listing Available Tools ===")

    val requestId = 2
    val listRequest: java.util.Map[String, Any] = Map(
      "jsonrpc" -> "2.0",
      "id" -> requestId,
      "method" -> "tools/list",
      "params" -> Map[String, Any]().asJava
    ).asJava.asInstanceOf[java.util.Map[String, Any]]

    val startTime = System.currentTimeMillis()

    sendRequestWithTracking(serverUrl, sessionId, listRequest, "List Tools").flatMap { response =>
      val elapsed = System.currentTimeMillis() - startTime

      // Validate response
      if (response.has("result")) {
        val result = response.get("result")
        if (result.has("tools")) {
          val tools = result.get("tools")
          val toolCount = tools.size()

          logger.info(s"✓ Received tools list: $toolCount tools")
          tools.elements().asScala.foreach { tool =>
            logger.info(s"    - ${tool.get("name").asText()}: ${tool.get("description").asText()}")
          }

          // Validate we have expected tools
          val expectedTools = Set("echo", "system_info")
          val actualTools = tools.elements().asScala.map(_.get("name").asText()).toSet
          val hasAllTools = expectedTools.subsetOf(actualTools)

          testResults.add(TestResult(
            testName = "List Tools",
            requestId = requestId,
            success = hasAllTools,
            message = if (hasAllTools) s"Found all expected tools ($toolCount total)" else s"Missing tools: ${expectedTools.diff(actualTools).mkString(", ")}",
            responseTimeMs = elapsed
          ))

          Future.successful(())
        } else {
          val msg = "Response missing 'tools' field"
          logger.error(s"✗ $msg")
          testResults.add(TestResult("List Tools", requestId, false, msg, elapsed))
          Future.failed(new RuntimeException(msg))
        }
      } else if (response.has("error")) {
        val error = response.get("error")
        val msg = s"Error response: ${error.get("message").asText()}"
        logger.error(s"✗ $msg")
        testResults.add(TestResult("List Tools", requestId, false, msg, elapsed))
        Future.failed(new RuntimeException(msg))
      } else {
        val msg = "Invalid response format"
        logger.error(s"✗ $msg")
        testResults.add(TestResult("List Tools", requestId, false, msg, elapsed))
        Future.failed(new RuntimeException(msg))
      }
    }
  }

  /**
   * Test the echo tool with multiple messages
   */
  private def testEchoTool(serverUrl: String, sessionId: String): Future[Unit] = {
    logger.info("=== TEST: Echo Tool (Multiple Messages) ===")

    val testMessages = List(
      "Hello, Streaming HTTP!",
      "Testing SSE transport",
      "Pekko HTTP + MCP!"
    )

    val futures = testMessages.zipWithIndex.map { case (message, index) =>
      val requestId = 10 + index
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

      val startTime = System.currentTimeMillis()

      sendRequestWithTracking(serverUrl, sessionId, callRequest, s"Echo Tool #${index + 1}").flatMap { response =>
        val elapsed = System.currentTimeMillis() - startTime

        // Validate echo response
        if (response.has("result")) {
          val result = response.get("result")
          if (result.has("content")) {
            val content = result.get("content")
            val textItems = content.elements().asScala.filter(_.get("type").asText() == "text").toList

            if (textItems.nonEmpty) {
              val echoedText = textItems.head.get("text").asText()
              val expectedEcho = s"Echo: $message"
              val matches = echoedText == expectedEcho

              logger.info(s"  Input:    '$message'")
              logger.info(s"  Output:   '$echoedText'")
              logger.info(s"  Expected: '$expectedEcho'")
              logger.info(s"  Match:    ${if (matches) "✓" else "✗"}")

              testResults.add(TestResult(
                testName = s"Echo Tool #${index + 1}: '$message'",
                requestId = requestId,
                success = matches,
                message = if (matches) "Echo matches expected output" else s"Expected '$expectedEcho' but got '$echoedText'",
                responseTimeMs = elapsed
              ))

              Future.successful(())
            } else {
              val msg = "No text content in response"
              logger.error(s"✗ $msg")
              testResults.add(TestResult(s"Echo Tool #${index + 1}", requestId, false, msg, elapsed))
              Future.failed(new RuntimeException(msg))
            }
          } else {
            val msg = "Response missing 'content' field"
            logger.error(s"✗ $msg")
            testResults.add(TestResult(s"Echo Tool #${index + 1}", requestId, false, msg, elapsed))
            Future.failed(new RuntimeException(msg))
          }
        } else if (response.has("error")) {
          val error = response.get("error")
          val msg = s"Error: ${error.get("message").asText()}"
          logger.error(s"✗ $msg")
          testResults.add(TestResult(s"Echo Tool #${index + 1}", requestId, false, msg, elapsed))
          Future.failed(new RuntimeException(msg))
        } else {
          val msg = "Invalid response format"
          logger.error(s"✗ $msg")
          testResults.add(TestResult(s"Echo Tool #${index + 1}", requestId, false, msg, elapsed))
          Future.failed(new RuntimeException(msg))
        }
      }.recover {
        case ex: Exception =>
          val elapsed = System.currentTimeMillis() - startTime
          logger.error(s"✗ Echo test #${index + 1} failed: ${ex.getMessage}")
          testResults.add(TestResult(s"Echo Tool #${index + 1}", requestId, false, ex.getMessage, elapsed))
      }
    }

    Future.sequence(futures).map(_ => ())
  }

  /**
   * Test the system_info tool
   */
  private def testSystemInfoTool(serverUrl: String, sessionId: String): Future[Unit] = {
    logger.info("=== TEST: System Info Tool ===")

    val requestId = 20
    val callRequest: java.util.Map[String, Any] = Map(
      "jsonrpc" -> "2.0",
      "id" -> requestId,
      "method" -> "tools/call",
      "params" -> Map(
        "name" -> "system_info",
        "arguments" -> Map[String, Any]().asJava
      ).asJava
    ).asJava.asInstanceOf[java.util.Map[String, Any]]

    val startTime = System.currentTimeMillis()

    sendRequestWithTracking(serverUrl, sessionId, callRequest, "System Info Tool").flatMap { response =>
      val elapsed = System.currentTimeMillis() - startTime

      // Validate system info response
      if (response.has("result")) {
        val result = response.get("result")
        if (result.has("content")) {
          val content = result.get("content")
          val textItems = content.elements().asScala.filter(_.get("type").asText() == "text").toList

          if (textItems.nonEmpty) {
            val sysInfo = textItems.head.get("text").asText()

            // Validate system info contains expected fields
            val expectedFields = List("OS Name:", "Java Version:", "Available Processors:", "Total Memory:")
            val missingFields = expectedFields.filterNot(sysInfo.contains)
            val hasAllFields = missingFields.isEmpty

            logger.info("System Information:")
            logger.info(sysInfo)

            testResults.add(TestResult(
              testName = "System Info Tool",
              requestId = requestId,
              success = hasAllFields,
              message = if (hasAllFields) "Contains all expected fields" else s"Missing fields: ${missingFields.mkString(", ")}",
              responseTimeMs = elapsed
            ))

            Future.successful(())
          } else {
            val msg = "No text content in response"
            logger.error(s"✗ $msg")
            testResults.add(TestResult("System Info Tool", requestId, false, msg, elapsed))
            Future.failed(new RuntimeException(msg))
          }
        } else {
          val msg = "Response missing 'content' field"
          logger.error(s"✗ $msg")
          testResults.add(TestResult("System Info Tool", requestId, false, msg, elapsed))
          Future.failed(new RuntimeException(msg))
        }
      } else if (response.has("error")) {
        val error = response.get("error")
        val msg = s"Error: ${error.get("message").asText()}"
        logger.error(s"✗ $msg")
        testResults.add(TestResult("System Info Tool", requestId, false, msg, elapsed))
        Future.failed(new RuntimeException(msg))
      } else {
        val msg = "Invalid response format"
        logger.error(s"✗ $msg")
        testResults.add(TestResult("System Info Tool", requestId, false, msg, elapsed))
        Future.failed(new RuntimeException(msg))
      }
    }.recover {
      case ex: Exception =>
        val elapsed = System.currentTimeMillis() - startTime
        logger.error(s"✗ System info test failed: ${ex.getMessage}")
        testResults.add(TestResult("System Info Tool", requestId, false, ex.getMessage, elapsed))
        ()
    }
  }

  /**
   * Send a request with tracking for response validation
   */
  private def sendRequestWithTracking(
    serverUrl: String,
    sessionId: String,
    requestBody: java.util.Map[String, Any],
    testName: String
  ): Future[JsonNode] = {
    val requestId = requestBody.get("id").asInstanceOf[Int]
    val promise = Promise[JsonNode]()

    // Register pending request
    pendingRequests.put(requestId, promise)
    expectedResponses.incrementAndGet()

    val requestJson = jsonMapper.writeValueAsString(requestBody)
    logger.debug(s"Sending request ID $requestId: $testName")

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
          logger.debug(s"Request $requestId accepted, waiting for SSE response...")
          promise.future

        case StatusCodes.OK =>
          // Direct response (shouldn't happen in streaming mode, but handle it)
          logger.debug(s"Request $requestId got direct response")
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
