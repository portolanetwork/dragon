package app.dragon.turnstile.mcp

import app.dragon.turnstile.service.McpService
import com.typesafe.config.Config
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.{McpServer, McpSyncServer}
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.{Logger, LoggerFactory}
import scala.jdk.CollectionConverters.*

/**
 * MCP Server implementation using HTTP streaming with Server-Sent Events (SSE).
 *
 * This server provides streaming HTTP transport for the Model Context Protocol using:
 * - Server-Sent Events (SSE) for server-to-client messages
 * - HTTP POST for client-to-server messages
 * - Session-based communication with persistent connections
 * - Message replay support
 *
 * Key Features:
 * - Bidirectional streaming over HTTP
 * - Multiple concurrent client sessions
 * - Session management with mcp-session-id headers
 * - Message buffering and replay on reconnection
 * - Graceful shutdown
 *
 * Transport Details:
 * - GET /mcp: Establish SSE connection (with mcp-session-id header)
 * - POST /mcp: Send messages (initialize creates session, others require mcp-session-id)
 * - DELETE /mcp: Close session (with mcp-session-id header)
 *
 * This is the streaming equivalent of the stateless HTTP server, providing:
 * - Long-lived connections instead of request/response pairs
 * - Server-initiated messages (notifications, progress updates)
 * - Better performance for interactive applications
 * - Automatic reconnection with message replay
 *
 * Usage:
 * {{{
 * val config = ApplicationConfig.rootConfig.getConfig("turnstile.mcp-streaming")
 * val server = StreamingHttpMcpServer(config)
 * server.start()
 * }}}
 */
class StreamingHttpMcpServer(config: Config)(implicit system: ActorSystem[?]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[StreamingHttpMcpServer])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")
  private val host = config.getString("host")
  private val port = config.getInt("port")

  // Create the service layer for tool handlers
  private val mcpService = McpService(config)

  // Create the JSON mapper
  private val jsonMapper = McpJsonMapper.getDefault

  // Create the streaming HTTP transport provider
  private val transportProvider = PekkoHttpStreamableServerTransportProvider.builder()
    .jsonMapper(jsonMapper)
    .mcpEndpoint("/mcp")
    .host(host)
    .port(port)
    .disallowDelete(false)
    .build()

  // Build the sync server using the SDK builder pattern
  private val mcpServer: McpSyncServer = {
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
   * Start the MCP streaming HTTP server
   */
  def start(): Unit = {
    logger.info(s"Starting MCP Streaming HTTP Server: $serverName v$serverVersion")
    logger.info("Using HTTP streaming with Server-Sent Events (SSE)")
    logger.info(s"Server will be available at http://$host:$port/mcp")
    logger.info("Client should:")
    logger.info("  1. POST to /mcp with initialize request")
    logger.info("  2. Receive mcp-session-id in response header")
    logger.info("  3. GET /mcp with mcp-session-id header to establish SSE stream")
    logger.info("  4. POST to /mcp with mcp-session-id header to send requests")

    transportProvider.start()

    logger.info("MCP Streaming HTTP Server started successfully")
    logger.info("Available tools: " + mcpServer.listTools().asScala.map(_.name()).mkString(", "))
  }

  /**
   * Stop the MCP streaming HTTP server
   */
  def stop(): Unit = {
    logger.info("Stopping MCP Streaming HTTP Server")
    try {
      transportProvider.closeGracefully().block()
      logger.info("MCP Streaming HTTP Server stopped gracefully")
    } catch {
      case ex: Exception =>
        logger.error("Error stopping MCP Streaming HTTP Server", ex)
    }
  }
}

object StreamingHttpMcpServer {
  def apply(config: Config)(implicit system: ActorSystem[?]): StreamingHttpMcpServer = {
    new StreamingHttpMcpServer(config)
  }
}
