package app.dragon.turnstile.mcp

import app.dragon.turnstile.service.McpService
import com.typesafe.config.Config
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.{Logger, LoggerFactory}
import scala.jdk.CollectionConverters.*

/**
 * MCP Server implementation using stdio transport for process-to-process communication.
 * Provides tools and resources via Model Context Protocol over stdin/stdout.
 *
 * This implementation uses:
 * - io.modelcontextprotocol.sdk for MCP protocol handling
 * - StdioServerTransportProvider from MCP SDK for true streaming stdin/stdout
 * - McpServer.sync() builder for session-based design
 * - McpService for tool definitions and handlers
 *
 * Implements streaming stdio transport for local process communication:
 * - Bidirectional JSON-RPC message streaming over stdin/stdout
 * - Non-blocking message processing with reactive streams
 * - Session-based request handling
 * - Compliant with MCP specification
 *
 * Key Features:
 * - True streaming: messages are processed reactively as they arrive
 * - Non-blocking: uses Project Reactor for async processing
 * - Session management: maintains state across multiple requests
 * - Standard SDK transport: uses official StdioServerTransportProvider
 *
 * Usage:
 * - Start the server programmatically via Guardian
 * - Or run as a standalone process communicating via stdio
 * - Useful for CLI tools, testing, and local integrations
 *
 * Architecture:
 * - StdioMcpServer: Server lifecycle management
 * - McpService: Business logic for tool definitions and handlers
 * - StdioServerTransportProvider: Official SDK stdio transport (streaming)
 */
class StdioMcpServer(config: Config)(implicit system: ActorSystem[?]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[StdioMcpServer])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")

  // Create the service layer for tool handlers
  private val mcpService = McpService(config)

  // Create the JSON mapper for MCP protocol
  private val jsonMapper = McpJsonMapper.getDefault

  // Create the streaming stdio transport provider using official SDK
  private val transportProvider = new StdioServerTransportProvider(jsonMapper)

  // Build the session-based sync server using the SDK builder pattern
  // This creates a session-based server (not stateless) which supports streaming
  private val mcpServer: io.modelcontextprotocol.server.McpSyncServer = {
    var builder = McpServer.sync(transportProvider)
      .serverInfo(serverName, serverVersion)

    // Register all tools from the service
    // Convert stateless handlers to session-based handlers
    mcpService.getToolsWithHandlers.foreach { case (tool, statelessHandler) =>
      // Wrap the stateless handler to work with session-based API
      val sessionHandler = new java.util.function.BiFunction[
        io.modelcontextprotocol.server.McpSyncServerExchange,
        io.modelcontextprotocol.spec.McpSchema.CallToolRequest,
        io.modelcontextprotocol.spec.McpSchema.CallToolResult
      ] {
        override def apply(
          exchange: io.modelcontextprotocol.server.McpSyncServerExchange,
          req: io.modelcontextprotocol.spec.McpSchema.CallToolRequest
        ): io.modelcontextprotocol.spec.McpSchema.CallToolResult = {
          // Extract transport context from exchange and delegate to stateless handler
          statelessHandler.apply(exchange.transportContext(), req)
        }
      }

      builder = builder.toolCall(tool, sessionHandler)
    }

    builder.build()
  }

  /**
   * Start the MCP stdio server
   * The server automatically starts listening on stdin/stdout when created
   */
  def start(): Unit = {
    logger.info(s"Starting MCP Stdio Server: $serverName v$serverVersion")
    logger.info("MCP Server using streaming stdio transport (StdioServerTransportProvider)")
    logger.info("Server will communicate via stdin/stdout with non-blocking message processing")
    logger.info("Available tools: " + mcpServer.listTools().asScala.map(_.name()).mkString(", "))
  }

  /**
   * Stop the MCP stdio server
   */
  def stop(): Unit = {
    logger.info("Stopping MCP Stdio Server")
    try {
      transportProvider.closeGracefully().block()
      logger.info("MCP Stdio Server stopped gracefully")
    } catch {
      case ex: Exception =>
        logger.error("Error stopping MCP Stdio Server", ex)
        transportProvider.close()
    }
  }
}

object StdioMcpServer {
  def apply(config: Config)(implicit system: ActorSystem[?]): StdioMcpServer = {
    new StdioMcpServer(config)
  }
}
