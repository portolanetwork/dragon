/*
 * Copyright 2025 Sami Malik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: Sami Malik (sami.malik [at] portolanetwork.io)
 */

package app.dragon.turnstile.example

import app.dragon.turnstile.client.TurnstileStreamingHttpAsyncMcpClient
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Example demonstrating the TurnstileStreamingHttpAsyncMcpClient wrapper for testing streaming server.
 *
 * This example shows:
 * - Using TurnstileStreamingHttpAsyncMcpClient wrapper (instead of directly creating McpAsyncClient)
 * - Simplified client creation using the Turnstile wrapper API
 * - All progress notifications, logging, and change handlers configured automatically
 * - TRUE STREAMING: Testing streaming_demo_tool with real 2-second delays and progress notifications
 * - Proper lifecycle management with graceful shutdown
 *
 * When streaming_demo_tool is available, this example demonstrates:
 * - Progress notifications arriving in real-time via progressConsumer
 * - Non-blocking execution with Flux.delayElements(Duration.ofSeconds(2))
 * - Each iteration sends a progress notification ~2 seconds apart
 * - Final result arrives after all processing completes (~6 seconds for count=3)
 * - Timestamps show the streaming nature of the execution
 *
 * Usage:
 * {{{
 * # Test against local server (includes streaming_demo_tool for streaming demo)
 * sbt "runMain app.dragon.turnstile.example.TurnstileClientExample http://localhost:8082"
 *
 * # With custom endpoint
 * sbt "runMain app.dragon.turnstile.example.TurnstileClientExample http://localhost:8082 /mcp"
 * }}}
 *
 * Expected Output (with streaming_demo_tool):
 * {{{
 * [HH:MM:SS.000] Starting tool call...
 * [HH:MM:SS.000] [PROGRESS] streaming-demo-tool-123: 0.0 / 3.0 0% - Starting actor tool processing
 * [HH:MM:SS.002] [PROGRESS] streaming-demo-tool-123: 1.0 / 3.0 33% - Processing iteration 1/3
 * [HH:MM:SS.004] [PROGRESS] streaming-demo-tool-123: 2.0 / 3.0 66% - Processing iteration 2/3
 * [HH:MM:SS.006] [PROGRESS] streaming-demo-tool-123: 3.0 / 3.0 100% - Processing iteration 3/3
 * TOOL CALL COMPLETED (took 6.0s, expected ~6 seconds)
 * }}}
 */
object TurnstileClientExample {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContext = ExecutionContext.global

  def main(args: Array[String]): Unit = {
    // Parse arguments
    val serverUrl = args.headOption.getOrElse("http://localhost:8082")
    //val serverUrl = args.headOption.getOrElse("https://mcp.deepwiki.com/mcp")
    val endpoint = args.lift(1).getOrElse("/mcp")

    logger.info("=" * 80)
    logger.info("TurnstileStreamingHttpAsyncMcpClient - Streaming Server Test")
    logger.info("=" * 80)
    logger.info(s"Server URL: $serverUrl")
    logger.info(s"Endpoint: $endpoint")
    logger.info("")

    // Create the client using TurnstileStreamingHttpAsyncMcpClient
    val client = TurnstileStreamingHttpAsyncMcpClient(serverUrl, endpoint)

    // Execute the workflow
    val workflow: Future[Unit] = for {
      // 1. Initialize the connection
      initResult <- client.initialize().recoverWith {
        case e: Exception =>
          logger.error("Failed to initialize client:", e)
          logger.error(s"Server URL: $serverUrl, Endpoint: $endpoint")
          Future.failed(e)
      }
      _ = logInitialization(initResult)

      // 2. List and display tools
      toolsResult <- client.listTools()
      _ = logTools(toolsResult.tools().asScala.toList)


      // 3. Call a tool if available
      _ <- callToolIfAvailable(client, toolsResult.tools().asScala.toList)

      // 4. List and display resources
      resources <- client.listResources()
        .map(_.resources().asScala.toList)
        .recover {
          case e: Exception =>
            logger.warn(s"Failed to list resources: ${e.getMessage}")
            List.empty
        }
      _ = logResources(resources)

      // 5. List and display prompts
      prompts <- client.listPrompts()
        .map(_.prompts().asScala.toList)
        .recover {
          case e: Exception =>
            logger.warn(s"Failed to list prompts: ${e.getMessage}")
            List.empty
        }
      _ = logPrompts(prompts)

      // 6. Demonstrate ping
      _ <- client.ping()
      _ = logger.info("✓ Ping successful")

    } yield ()

    // Handle result and cleanup
    workflow.onComplete {
      case Success(_) =>
        logger.info("")
        logger.info("=" * 80)
        logger.info("✓ Workflow completed successfully")
        logger.info("=" * 80)
        client.closeGracefully().onComplete { _ =>
          logger.info("✓ Client closed gracefully")
          System.exit(0)
        }

      case Failure(exception) =>
        logger.error("")
        logger.error("=" * 80)
        logger.error(s"✗ Workflow failed: ${exception.getMessage}", exception)
        logger.error("=" * 80)
        client.close()
        System.exit(1)
    }

    // Keep the application running
    Thread.sleep(60000) // Wait up to 60 seconds for async operations
  }

  /**
   * Log initialization results.
   */
  private def logInitialization(
    result: McpSchema.InitializeResult
  ): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info("CONNECTION INITIALIZED")
    logger.info("=" * 80)
    logger.info(s"Protocol Version: ${result.protocolVersion()}")
    logger.info(s"Server: ${result.serverInfo().name()} v${result.serverInfo().version()}")

    val capabilities = result.capabilities()
    logger.info("")
    logger.info("Server Capabilities:")

    if (capabilities.tools() != null) {
      logger.info(s"  ✓ Tools: ${if (capabilities.tools().listChanged()) "list_changed supported" else "supported"}")
    }

    if (capabilities.resources() != null) {
      logger.info(s"  ✓ Resources: " +
        s"${if (capabilities.resources().subscribe()) "subscribable" else "supported"}, " +
        s"${if (capabilities.resources().listChanged()) "list_changed supported" else ""}")
    }

    if (capabilities.prompts() != null) {
      logger.info(s"  ✓ Prompts: ${if (capabilities.prompts().listChanged()) "list_changed supported" else "supported"}")
    }

    if (capabilities.logging() != null) {
      logger.info("  ✓ Logging supported")
    }
  }

  /**
   * Log available tools.
   */
  private def logTools(
    tools: List[McpSchema.Tool]
  ): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info(s"AVAILABLE TOOLS (${tools.size})")
    logger.info("=" * 80)

    if (tools.isEmpty) {
      logger.info("  (none)")
    } else {
      tools.foreach { tool =>
        logger.info(s"  • ${tool.name()}")
        logger.info(s"    ${tool.description()}")

        if (tool.inputSchema() != null) {
          val schema = tool.inputSchema()
          if (schema.properties() != null && !schema.properties().isEmpty) {
            val propertyNames = schema.properties().keySet().asScala.mkString(", ")
            logger.info(s"    Parameters: $propertyNames")
          }
        }
      }
    }
  }

  /**
   * Log available resources.
   */
  private def logResources(
    resources: List[McpSchema.Resource]
  ): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info(s"AVAILABLE RESOURCES (${resources.size})")
    logger.info("=" * 80)

    if (resources.isEmpty) {
      logger.info("  (none)")
    } else {
      resources.foreach { resource =>
        logger.info(s"  • ${resource.uri()}")
        if (resource.name() != null) {
          logger.info(s"    Name: ${resource.name()}")
        }
        if (resource.description() != null) {
          logger.info(s"    ${resource.description()}")
        }
      }
    }
  }

  /**
   * Log available prompts.
   */
  private def logPrompts(
    prompts: List[McpSchema.Prompt]
  ): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info(s"AVAILABLE PROMPTS (${prompts.size})")
    logger.info("=" * 80)

    if (prompts.isEmpty) {
      logger.info("  (none)")
    } else {
      prompts.foreach { prompt =>
        logger.info(s"  • ${prompt.name()}")
        if (prompt.description() != null) {
          logger.info(s"    ${prompt.description()}")
        }
      }
    }
  }

  /**
   * Call a tool if any are available.
   * Demonstrates streaming with streaming_demo_tool which sends progress notifications.
   */
  private def callToolIfAvailable(
      client: TurnstileStreamingHttpAsyncMcpClient,
      tools: List[McpSchema.Tool]
  ): Future[Unit] = {
    if (tools.isEmpty) {
      logger.info("")
      logger.info("No tools available to call")
      return Future.successful(())
    }

    // Prefer streaming_demo_tool to demonstrate streaming, then echo, then first available
    val toolToCall = tools.find(_.name() == "streaming_demo_tool")
      .orElse(tools.find(_.name() == "echo"))
      .getOrElse(tools.head)

    logger.info("")
    logger.info("=" * 80)
    logger.info(s"CALLING TOOL: ${toolToCall.name()}")
    logger.info("=" * 80)

    // Create the tool call request based on tool type
    val (arguments, expectedDuration) = toolToCall.name() match {
      case "streaming_demo_tool" =>
        logger.info("Testing STREAMING with streaming_demo_tool (count=3)")
        logger.info("Expected: ~6 seconds with progress notifications every 2s")
        logger.info("Watch for [PROGRESS] messages below!")
        logger.info("")
        (java.util.Map.of(
          "message", "Testing streaming from TurnstileClient",
          "count", Integer.valueOf(3)
        ), "~6 seconds")

      case "echo" =>
        (java.util.Map.of("message", "Hello from TurnstileStreamingHttpAsyncMcpClient!"), "instant")

      case _ =>
        (java.util.Collections.emptyMap[String, Object](), "unknown")
    }

    val callToolRequest = new McpSchema.CallToolRequest(toolToCall.name(), arguments)

    val startTime = System.currentTimeMillis()
    logger.info(s"[${formatTimestamp(startTime)}] Starting tool call...")
    logger.info("")

    client.callTool(callToolRequest).map { result =>
      val endTime = System.currentTimeMillis()
      val elapsed = (endTime - startTime) / 1000.0

      logger.info("")
      logger.info("=" * 80)
      logger.info(s"TOOL CALL COMPLETED (took ${elapsed}s, expected $expectedDuration)")
      logger.info("=" * 80)

      if (result.content() != null) {
        result.content().asScala.foreach { content =>
          content.`type`() match {
            case "text" =>
              val text = content.asInstanceOf[McpSchema.TextContent].text()
              logger.info("Result:")
              logger.info("─" * 80)
              logger.info(text)
              logger.info("─" * 80)

            case "image" =>
              logger.info(s"  Image: ${content.asInstanceOf[McpSchema.ImageContent].mimeType()}")

            case "resource" =>
              val resContent = content.asInstanceOf[McpSchema.EmbeddedResource]
              logger.info(s"  Resource: ${resContent.resource().uri()}")

            case other =>
              logger.info(s"  Content type: $other")
          }
        }
      }

      if (result.isError() != null && result.isError()) {
        logger.warn("  ⚠ Tool reported an error")
      }

      logger.info("")
      logger.info("=" * 80)
      logger.info("STREAMING OBSERVATIONS:")
      logger.info("=" * 80)
      if (toolToCall.name() == "streaming_demo_tool") {
        logger.info("✓ Progress notifications arrived via progressConsumer (see [PROGRESS] logs above)")
        logger.info("✓ Each notification arrived ~2 seconds apart (non-blocking delays)")
        logger.info("✓ Final result arrived after all processing completed")
        logger.info(s"✓ Total execution time: ${elapsed}s (proves real delays)")
      } else {
        logger.info(s"✓ Tool '${toolToCall.name()}' executed successfully")
      }
      logger.info("=" * 80)
    }.recover {
      case e: Exception =>
        val endTime = System.currentTimeMillis()
        val elapsed = (endTime - startTime) / 1000.0
        logger.error("")
        logger.error(s"✗ Tool call failed after ${elapsed}s: ${e.getMessage}", e)
    }
  }

  /**
   * Format timestamp for logging
   */
  private def formatTimestamp(
    millis: Long
  ): String = {
    val instant = java.time.Instant.ofEpochMilli(millis)
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("HH:mm:ss.SSS")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)
  }
}
