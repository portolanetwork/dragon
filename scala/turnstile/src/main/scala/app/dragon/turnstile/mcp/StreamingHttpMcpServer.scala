package app.dragon.turnstile.mcp

import app.dragon.turnstile.service.ToolsService
import com.typesafe.config.Config
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.{McpAsyncServer, McpServer}
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.{Logger, LoggerFactory}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * MCP Server implementation using Streamable HTTP transport (MCP 2025-03-26).
 *
 * This server provides Streamable HTTP transport for the Model Context Protocol,
 * implementing the current MCP specification that replaces the deprecated HTTP+SSE
 * transport from protocol version 2024-11-05.
 *
 * Streamable HTTP Transport Features:
 * - Single unified endpoint supporting POST and GET methods
 * - HTTP POST for client-to-server messages (with Accept: application/json, text/event-stream)
 * - HTTP GET for server-to-client SSE streams (with Accept: text/event-stream)
 * - Session-based communication with mcp-session-id headers
 * - Message replay support via Last-Event-ID
 * - Multiple concurrent client sessions
 * - Graceful shutdown
 *
 * Protocol Endpoints:
 * - POST /mcp: Send messages (initialize creates session, others require mcp-session-id)
 *   - Initialize: Returns JSON response with mcp-session-id header
 *   - Responses/Notifications: Returns HTTP 202 Accepted
 *   - Requests: Returns text/event-stream with responses
 * - GET /mcp: Establish SSE connection (requires existing mcp-session-id header)
 * - DELETE /mcp: Close session (with mcp-session-id header)
 * - POST /messages: Cloud connector compatibility endpoint (same as POST /mcp)
 * - GET /messages: Cloud connector compatibility endpoint (same as GET /mcp)
 *
 * Key Benefits over Stateless HTTP:
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

  // Execution context for async operations
  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")
  private val host = config.getString("host")
  private val port = config.getInt("port")

  // Use the singleton ToolsService instance for tool management
  private val toolsService = ToolsService.instance

  // Create the JSON mapper
  private val jsonMapper = McpJsonMapper.getDefault

  // Read SSL configuration if present
  private val sslConfig = if (config.hasPath("ssl") && config.getBoolean("ssl.enabled")) {
    Some(SslConfig(
      enabled = true,
      keyStorePath = config.getString("ssl.keystore-path"),
      keyStorePassword = config.getString("ssl.keystore-password"),
      keyStoreType = if (config.hasPath("ssl.keystore-type"))
        config.getString("ssl.keystore-type")
      else "PKCS12"
    ))
  } else {
    None
  }

  // Create the streaming HTTP transport provider
  private val transportProvider = {
    val builder = PekkoHttpStreamableServerTransportProvider.builder()
      .jsonMapper(jsonMapper)
      .mcpEndpoint("/mcp")
      .host(host)
      .port(port)
      .disallowDelete(false)

    // Add SSL configuration if present
    val builderWithSsl = sslConfig match {
      case Some(ssl) => builder.withSslConfig(ssl)
      case None => builder
    }

    builderWithSsl.build()
  }

  // Build the async server with capabilities configuration
  private val mcpServer: McpAsyncServer = {
    McpServer
      .async(transportProvider)
      .serverInfo(serverName, serverVersion)
      .capabilities(McpSchema.ServerCapabilities.builder()
        .resources(false, true)  // Enable resource support with subscribe capability
        .tools(true)             // Enable tool support
        .prompts(true)           // Enable prompt support
        .logging()               // Enable logging support
        .completions()           // Enable completions support
        .build())
      .build()
  }

  /**
   * Start the MCP streaming HTTP server
   */
  def start(): Unit = {
    logger.info(s"Starting MCP Streamable HTTP Server: $serverName v$serverVersion")
    logger.info("Using Streamable HTTP transport (MCP 2025-03-26)")

    val protocol = if (sslConfig.exists(_.enabled)) "https" else "http"
    logger.info(s"Server will be available at $protocol://$host:$port/mcp")

    if (sslConfig.exists(_.enabled)) {
      logger.info(s"HTTPS/TLS enabled")
      logger.info(s"  KeyStore: ${sslConfig.get.keyStorePath}")
      logger.info(s"  KeyStore Type: ${sslConfig.get.keyStoreType}")
    }

    logger.info("Streamable HTTP Protocol Flow:")
    logger.info("  1. POST to /mcp with initialize request (Accept: application/json, text/event-stream)")
    logger.info("  2. Receive mcp-session-id in response header")
    logger.info("  3. GET /mcp with mcp-session-id header to establish SSE stream (Accept: text/event-stream)")
    logger.info("  4. POST to /mcp with mcp-session-id header to send requests/responses/notifications")
    logger.info("")
    logger.info("Cloud Connector Compatibility: /messages endpoint mirrors /mcp functionality")

    // Start the transport provider
    transportProvider.start()

    // Register all tools dynamically
    logger.info("Registering tools...")

    toolsService.getAsyncToolsSpec("default").foreach { toolSpec =>
      mcpServer.addTool(toolSpec)
        .doOnSuccess(_ => logger.info(s"Tool registered: ${toolSpec.tool().name()}"))
        .doOnError(ex => logger.error(s"Failed to register tool: ${toolSpec.tool().name()}", ex))
        .subscribe()
    }

    logger.info("MCP Streaming HTTP Server started successfully")
    // listTools() returns a Flux in the async API, so we need to collect it
    val tools = mcpServer.listTools().collectList().block()
    logger.info("Available tools: " + tools.asScala.map(_.name()).mkString(", "))
  }

  /**
   * Stop the MCP streaming HTTP server
   */
  def stop(): Unit = {
    logger.info("Stopping MCP Streaming HTTP Server")
    try {
      // Close the async server gracefully
      mcpServer.closeGracefully()
        .doOnSuccess(_ => logger.info("MCP server closed"))
        .doOnError(ex => logger.error("Error closing MCP server", ex))
        .subscribe()

      // Close the transport provider
      transportProvider.closeGracefully().block()

      // Note: Don't close singleton toolsService here - it's shared across the application
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
