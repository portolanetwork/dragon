package app.dragon.turnstile.client

import io.modelcontextprotocol.client.{McpClient, McpSyncClient}
import io.modelcontextprotocol.client.transport.{ServerParameters, StdioClientTransport}
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}

/**
 * Stdio-based MCP Client for connecting to the Turnstile MCP Stdio Server.
 *
 * This client uses the official MCP Java SDK's StdioClientTransport to communicate
 * with an MCP server via stdin/stdout. It demonstrates:
 * - Launching a server process
 * - Establishing a bidirectional streaming connection
 * - Initializing the MCP session
 * - Listing and calling tools
 * - Graceful shutdown
 *
 * Usage:
 * {{{
 * // Connect to a running server via its command
 * sbt "runMain app.dragon.turnstile.client.StdioMcpClient"
 *
 * // Or with custom server command
 * sbt "runMain app.dragon.turnstile.client.StdioMcpClient 'sbt run'"
 * }}}
 *
 * Architecture:
 * - Uses StdioClientTransport from MCP SDK
 * - Launches server as subprocess
 * - Bidirectional JSON-RPC streaming over stdin/stdout
 * - Synchronous API for ease of use
 */
object StdioMcpClient {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting Turnstile MCP Stdio Client...")

    // Parse command line arguments
    val serverCommand = if (args.nonEmpty) args(0) else "sbt"
    val serverArgs = if (args.length > 1) args.tail.toList else List("run")

    logger.info(s"Server command: $serverCommand ${serverArgs.mkString(" ")}")

    // Run the client
    Try {
      runClient(serverCommand, serverArgs)
    } match {
      case Success(_) =>
        logger.info("\n=== MCP Stdio Client completed successfully ===")
      case Failure(exception) =>
        logger.error("MCP Stdio Client failed", exception)
        System.exit(1)
    }
  }

  /**
   * Run the MCP client workflow
   */
  private def runClient(command: String, args: List[String]): Unit = {
    // Create JSON mapper
    val jsonMapper = McpJsonMapper.getDefault

    // Configure server parameters
    val serverParams = ServerParameters.builder(command)
      .args(args.asJava)
      .build()

    logger.info("Creating stdio transport...")

    // Create stdio transport
    val transport = new StdioClientTransport(serverParams, jsonMapper)

    // Set error handler to log stderr from server
    transport.setStdErrorHandler(line => {
      if (!line.startsWith("[info]") && !line.startsWith("[warn]")) {
        logger.debug(s"[Server stderr] $line")
      }
    })

    logger.info("Creating MCP sync client...")

    // Create sync client
    val clientInfo = new McpSchema.Implementation("turnstile-stdio-test-client", "1.0.0")
    val client = McpClient.sync(transport)
      .clientInfo(clientInfo)
      .build()

    try {
      // Initialize connection
      logger.info("\n=== Initializing MCP Connection ===")
      val initResult = client.initialize()

      logger.info(s"Protocol Version: ${initResult.protocolVersion()}")
      logger.info(s"Server: ${initResult.serverInfo().name()} v${initResult.serverInfo().version()}")

      val capabilities = initResult.capabilities()
      logger.info(s"Server Capabilities:")
      if (capabilities.tools() != null) {
        logger.info(s"  - Tools: ${capabilities.tools()}")
      }
      if (capabilities.resources() != null) {
        logger.info(s"  - Resources: ${capabilities.resources()}")
      }
      if (capabilities.prompts() != null) {
        logger.info(s"  - Prompts: ${capabilities.prompts()}")
      }

      // List available tools
      logger.info("\n=== Listing Available Tools ===")
      val toolsResult = client.listTools()
      val tools = toolsResult.tools()

      logger.info(s"Found ${tools.size()} tools:")
      tools.asScala.foreach { tool =>
        logger.info(s"  - ${tool.name()}: ${tool.description()}")
      }

      // Test echo tool
      logger.info("\n=== Testing Echo Tool ===")
      testEchoTool(client)

      // Test system_info tool
      logger.info("\n=== Testing System Info Tool ===")
      testSystemInfoTool(client)

      logger.info("\n=== All Tests Completed Successfully ===")

    } finally {
      // Close the client gracefully
      logger.info("Closing MCP client...")
      try {
        client.close()
        logger.info("MCP client closed successfully")
      } catch {
        case ex: Exception =>
          logger.error("Error closing client", ex)
      }
    }
  }

  /**
   * Test the echo tool with various messages
   */
  private def testEchoTool(client: McpSyncClient): Unit = {
    val testMessages = List("Hello, Stdio MCP!", "Testing stdio transport", "Scala 3 + MCP SDK!")

    testMessages.foreach { message =>
      Try {
        val arguments: java.util.Map[String, Object] = Map[String, Object]("message" -> message).asJava

        val request = new McpSchema.CallToolRequest("echo", arguments)
        val result = client.callTool(request)

        result.content().asScala.foreach { content =>
          content match {
            case textContent: McpSchema.TextContent =>
              logger.info(s"  Input: '$message'")
              logger.info(s"  Output: '${textContent.text()}'")
            case _ =>
              logger.warn(s"  Unexpected content type: ${content.getClass.getSimpleName}")
          }
        }

        if (result.isError) {
          logger.error(s"  Tool returned error for message '$message'")
        }

      } match {
        case Failure(ex) =>
          logger.error(s"Failed to call echo tool with message '$message'", ex)
        case Success(_) => // Already logged
      }
    }
  }

  /**
   * Test the system_info tool
   */
  private def testSystemInfoTool(client: McpSyncClient): Unit = {
    Try {
      val arguments: java.util.Map[String, Object] = Map[String, Object]().asJava

      val request = new McpSchema.CallToolRequest("system_info", arguments)
      val result = client.callTool(request)

      result.content().asScala.foreach { content =>
        content match {
          case textContent: McpSchema.TextContent =>
            logger.info(s"System Information:\n${textContent.text()}")
          case _ =>
            logger.warn(s"Unexpected content type: ${content.getClass.getSimpleName}")
        }
      }

      if (result.isError) {
        logger.error("Tool returned error for system_info")
      }

    } match {
      case Failure(ex) =>
        logger.error("Failed to call system_info tool", ex)
      case Success(_) => // Already logged
    }
  }
}
