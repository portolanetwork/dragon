package app.dragon.turnstile.mcp

import app.dragon.turnstile.service.McpService
import com.typesafe.config.Config
import io.modelcontextprotocol.server.{McpServer, McpStatelessSyncServer}
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.{Logger, LoggerFactory}
import scala.jdk.CollectionConverters.*

/**
 * MCP Server implementation for Turnstile using the official Java SDK.
 * Provides tools and resources via Model Context Protocol over HTTP.
 *
 * This implementation uses:
 * - io.modelcontextprotocol.sdk for MCP protocol handling
 * - Custom Pekko HTTP transport (PekkoHttpStatelessServerTransport)
 * - McpServer.sync() builder for stateless, horizontally scalable design
 * - McpService for tool definitions and handlers (separation of concerns)
 *
 * Implements HTTP transport for internet accessibility:
 * - Single /mcp endpoint supporting POST for JSON-RPC
 * - Stateless request handling
 * - Compliant with MCP specification
 *
 * Architecture:
 * - TurnstileMcpServer: Transport and server lifecycle management
 * - McpService: Business logic for tool definitions and handlers
 * - PekkoHttpStatelessServerTransport: HTTP protocol adapter
 */
class TurnstileMcpServer(config: Config)(implicit system: ActorSystem[?]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpServer])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")
  private val host = config.getString("host")
  private val port = config.getInt("port")

  // Create the service layer for tool handlers
  private val mcpService = McpService(config)

  // Create the Pekko HTTP transport
  private val transport = new PekkoHttpStatelessServerTransport(host, port, "/mcp")(system)

  // Build the stateless sync server using the SDK builder pattern
  private val mcpServer: McpStatelessSyncServer = {
    var builder = McpServer.sync(transport).serverInfo(serverName, serverVersion)

    // Register all tools from the service
    mcpService.getToolsWithHandlers.foreach { case (tool, handler) =>
      builder = builder.toolCall(tool, handler)
    }

    builder.build()
  }

  /**
   * Start the MCP HTTP server
   */
  def start(): Unit = {
    logger.info(s"Starting MCP Server using official Java SDK: $serverName v$serverVersion")
    logger.info(s"MCP Server will be available via HTTP at http://$host:$port/mcp")

    transport.start()

    logger.info(s"MCP Server started successfully")
    logger.info("Available tools: " + mcpServer.listTools().asScala.map(_.name()).mkString(", "))
  }

  /**
   * Stop the MCP HTTP server
   */
  def stop(): Unit = {
    logger.info("Stopping MCP Server")
    mcpServer.close()
    logger.info("MCP Server stopped")
  }
}

object TurnstileMcpServer {
  def apply(config: Config)(implicit system: ActorSystem[?]): TurnstileMcpServer = {
    new TurnstileMcpServer(config)
  }
}
