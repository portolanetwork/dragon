package app.dragon.turnstile.example

import app.dragon.turnstile.client.{McpAsyncClient, McpHttpClientFactory}
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Example demonstrating connection to the local Turnstile Streaming HTTP MCP Server.
 *
 * This example connects to the Dragon MCP Streaming Server running on port 8082
 * and demonstrates:
 * - Establishing a streaming HTTP connection
 * - Initializing the MCP session
 * - Discovering available tools from the server
 * - Calling tools with various arguments
 * - Listing and reading resources
 * - Getting prompts
 * - Proper session cleanup
 *
 * Prerequisites:
 * 1. Start the Turnstile server which includes the streaming MCP server:
 *    `sbt run`
 *
 * 2. The server should be running on http://localhost:8082/mcp
 *
 * Usage:
 * {{{
 * sbt "runMain app.dragon.turnstile.example.ConnectToLocalStreamingServer"
 * }}}
 */
object ConnectToLocalStreamingServer extends App {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContext = ExecutionContext.global

  // Configuration for local streaming server
  val serverUrl = "http://localhost:8082/mcp"
  val clientName = "turnstile-local-client"
  val clientVersion = "1.0.0"

  logger.info("=" * 80)
  logger.info("Connecting to Local Dragon MCP Streaming Server")
  logger.info("=" * 80)
  logger.info(s"Server URL: $serverUrl")
  logger.info("")

  // Create the async client using streamable HTTP transport
  // The local server uses the streamable HTTP protocol (MCP 2025-03-26)
  val client = McpHttpClientFactory.createStreamableHttpClient(
    serverUrl = serverUrl,
    clientName = clientName,
    clientVersion = clientVersion
  )

  logger.info(s"Created MCP client: $clientName v$clientVersion")

  /**
   * Main workflow demonstrating all MCP operations
   */
  val workflow: Future[Unit] = {
    logger.info("\n[1/7] Initializing connection...")

    client.initialize().flatMap { initResult =>
      logger.info(s"✓ Connected successfully!")
      logger.info(s"  Server: ${initResult.serverInfo().name()} v${initResult.serverInfo().version()}")
      logger.info(s"  Protocol Version: ${initResult.protocolVersion()}")
      logger.info(s"  Capabilities: ${describeCapabilities(initResult.capabilities())}")
      if (initResult.instructions() != null) {
        logger.info(s"  Instructions: ${initResult.instructions()}")
      }

      logger.info("\n[2/7] Discovering available tools...")
      client.listTools()
    }.flatMap { tools =>
      logger.info(s"✓ Found ${tools.size} tool(s):")
      tools.foreach { tool =>
        logger.info(s"  • ${tool.name()}: ${tool.description()}")
        if (tool.inputSchema() != null) {
          logger.info(s"    Input schema: ${tool.inputSchema()}")
        }
      }

      logger.info("\n[3/7] Testing tool execution...")
      demonstrateToolCalls(client, tools)
    }.flatMap { _ =>
      logger.info("\n[4/7] Listing available resources...")
      client.listResources().recover {
        case e: Exception =>
          logger.warn(s"  Resources not available: ${e.getMessage}")
          List.empty
      }
    }.flatMap { resources =>
      if (resources.nonEmpty) {
        logger.info(s"✓ Found ${resources.size} resource(s):")
        resources.take(5).foreach { resource =>
          logger.info(s"  • ${resource.uri()}: ${resource.name()}")
          if (resource.description() != null) {
            logger.info(s"    ${resource.description()}")
          }
        }
        if (resources.size > 5) {
          logger.info(s"  ... and ${resources.size - 5} more")
        }
      } else {
        logger.info("  No resources available")
      }

      logger.info("\n[5/7] Listing resource templates...")
      client.listResourceTemplates().recover {
        case e: Exception =>
          logger.warn(s"  Resource templates not available: ${e.getMessage}")
          List.empty
      }
    }.flatMap { templates =>
      if (templates.nonEmpty) {
        logger.info(s"✓ Found ${templates.size} template(s):")
        templates.foreach { template =>
          logger.info(s"  • ${template.uriTemplate()}: ${template.name()}")
        }
      } else {
        logger.info("  No resource templates available")
      }

      logger.info("\n[6/7] Listing available prompts...")
      client.listPrompts().recover {
        case e: Exception =>
          logger.warn(s"  Prompts not available: ${e.getMessage}")
          List.empty
      }
    }.flatMap { prompts =>
      if (prompts.nonEmpty) {
        logger.info(s"✓ Found ${prompts.size} prompt(s):")
        prompts.foreach { prompt =>
          logger.info(s"  • ${prompt.name()}: ${prompt.description()}")
        }
      } else {
        logger.info("  No prompts available")
      }

      logger.info("\n[7/7] Testing server connectivity...")
      client.ping()
    }.map { _ =>
      logger.info("✓ Server ping successful")
    }
  }

  /**
   * Demonstrate calling various tools from the server
   */
  private def demonstrateToolCalls(
      client: McpAsyncClient,
      tools: List[io.modelcontextprotocol.spec.McpSchema.Tool]
  ): Future[Unit] = {
    if (tools.isEmpty) {
      logger.info("  No tools available to test")
      return Future.successful(())
    }

    // Try to call a few tools with sample data
    val toolTests = tools.take(3).map { tool =>
      val arguments = generateSampleArguments(tool)

      logger.info(s"\n  Testing tool: ${tool.name()}")
      logger.info(s"  Arguments: ${arguments}")

      client.callTool(tool.name(), arguments).map { result =>
        if (result.isError != null && result.isError) {
          logger.warn(s"  ✗ Tool returned error: ${result.content()}")
        } else {
          logger.info(s"  ✓ Tool executed successfully")
          logger.info(s"  Response: ${formatToolResult(result)}")
        }
        result
      }.recover {
        case e: Exception =>
          logger.warn(s"  ✗ Tool call failed: ${e.getMessage}")
          null
      }
    }

    Future.sequence(toolTests).map(_ => ())
  }

  /**
   * Generate sample arguments for a tool based on its schema
   */
  private def generateSampleArguments(
      tool: io.modelcontextprotocol.spec.McpSchema.Tool
  ): Map[String, Any] = {
    // For this example, we'll use simple defaults
    // In a real application, you'd parse the JSON schema and generate appropriate values
    tool.name() match {
      case "echo" => Map("message" -> "Hello from Turnstile client!")
      case "system_info" => Map.empty
      case "calculate" => Map("expression" -> "2 + 2")
      case "getCurrentTime" => Map.empty
      case "getUserInfo" => Map("userId" -> "default")
      case _ => Map.empty // Empty args for unknown tools
    }
  }

  /**
   * Format tool result for display
   */
  private def formatToolResult(
      result: io.modelcontextprotocol.spec.McpSchema.CallToolResult
  ): String = {
    if (result.content() != null && !result.content().isEmpty) {
      result.content().asScala.take(3).map { content =>
        s"    ${content.`type`()}: ${content}"
      }.mkString("\n")
    } else {
      "    (no content)"
    }
  }

  /**
   * Describe server capabilities in a human-readable format
   */
  private def describeCapabilities(
      caps: io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
  ): String = {
    val features = List(
      if (caps.tools() != null) Some("tools") else None,
      if (caps.resources() != null) Some("resources") else None,
      if (caps.prompts() != null) Some("prompts") else None,
      if (caps.logging() != null) Some("logging") else None
    ).flatten

    if (features.isEmpty) "none" else features.mkString(", ")
  }

  // Execute the workflow and handle results
  workflow.onComplete {
    case Success(_) =>
      logger.info("\n" + "=" * 80)
      logger.info("Successfully completed all MCP operations!")
      logger.info("=" * 80)

      // Gracefully close the connection
      logger.info("\nClosing connection...")
      client.closeGracefully().onComplete { _ =>
        logger.info("Connection closed gracefully")
        logger.info("\nExample completed successfully!")
        System.exit(0)
      }

    case Failure(exception) =>
      logger.error("\n" + "=" * 80)
      logger.error("MCP workflow failed!", exception)
      logger.error("=" * 80)
      logger.error(s"\nError: ${exception.getMessage}")

      // Check for common issues
      exception.getMessage match {
        case msg if msg.contains("Connection refused") =>
          logger.error("\n⚠ The MCP server is not running!")
          logger.error("Please start the Turnstile server first:")
          logger.error("  sbt run")

        case msg if msg.contains("timeout") =>
          logger.error("\n⚠ Connection timed out!")
          logger.error("The server may be overloaded or not responding")

        case _ =>
          logger.error("\nPlease check the server logs for more details")
      }

      client.close()
      System.exit(1)
  }

  // Keep the application running for async operations
  Thread.sleep(60000) // Wait up to 60 seconds
}
