package app.dragon.turnstile.mcp

import com.typesafe.config.Config
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.{McpServer, McpStatelessSyncServer}
import io.modelcontextprotocol.spec.McpSchema
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
 *
 * Implements HTTP transport for internet accessibility:
 * - Single /mcp endpoint supporting POST for JSON-RPC
 * - Stateless request handling
 * - Compliant with MCP specification
 *
 * Currently implements:
 * - echo: Simple message echo for testing
 * - system_info: Return server system information
 */
class TurnstileMcpServer(config: Config)(implicit system: ActorSystem[?]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpServer])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")
  private val host = config.getString("host")
  private val port = config.getInt("port")

  // Create the Pekko HTTP transport
  private val transport = new PekkoHttpStatelessServerTransport(host, port, "/mcp")(system)

  // Define tool handlers BEFORE using them in the builder
  private val echoToolHandler: java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult] =
    (ctx: McpTransportContext, request: McpSchema.CallToolRequest) => {
      val arguments = request.arguments()
      val message = if (arguments != null && arguments.containsKey("message")) {
        arguments.get("message").toString
      } else {
        ""
      }

      logger.debug(s"Echo tool called with message: $message")

      val content: java.util.List[McpSchema.Content] = List(
        new McpSchema.TextContent(s"Echo: $message")
      ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

      McpSchema.CallToolResult.builder()
        .content(content)
        .isError(false)
        .build()
    }

  private val systemInfoToolHandler: java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult] =
    (ctx: McpTransportContext, request: McpSchema.CallToolRequest) => {
      val runtime = Runtime.getRuntime
      val totalMemory = runtime.totalMemory()
      val freeMemory = runtime.freeMemory()
      val usedMemory = totalMemory - freeMemory
      val maxMemory = runtime.maxMemory()

      val info = s"""System Information:
                    |Server: $serverName v$serverVersion
                    |Java Version: ${System.getProperty("java.version")}
                    |Java Vendor: ${System.getProperty("java.vendor")}
                    |OS Name: ${System.getProperty("os.name")}
                    |OS Architecture: ${System.getProperty("os.arch")}
                    |OS Version: ${System.getProperty("os.version")}
                    |Available Processors: ${runtime.availableProcessors()}
                    |Total Memory: ${totalMemory / 1024 / 1024} MB
                    |Used Memory: ${usedMemory / 1024 / 1024} MB
                    |Free Memory: ${freeMemory / 1024 / 1024} MB
                    |Max Memory: ${maxMemory / 1024 / 1024} MB
                    |""".stripMargin

      logger.debug("System info tool called")

      val content: java.util.List[McpSchema.Content] = List(
        new McpSchema.TextContent(info)
      ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

      McpSchema.CallToolResult.builder()
        .content(content)
        .isError(false)
        .build()
    }

  // Create the stateless sync server using the builder pattern
  private val mcpServer: McpStatelessSyncServer = McpServer.sync(transport)
    .serverInfo(serverName, serverVersion)
    .toolCall(createEchoTool(), echoToolHandler)
    .toolCall(createSystemInfoTool(), systemInfoToolHandler)
    .build()

  /**
   * Create the echo tool definition
   */
  private def createEchoTool(): McpSchema.Tool = {
    val inputSchema = new McpSchema.JsonSchema(
      "object",
      Map[String, Object](
        "message" -> Map[String, Object](
          "type" -> "string",
          "description" -> "The message to echo back"
        ).asJava
      ).asJava,
      List("message").asJava,
      null, // additionalProperties
      null, // defs
      null  // definitions
    )

    McpSchema.Tool.builder()
      .name("echo")
      .description("Echoes back the provided message")
      .inputSchema(inputSchema)
      .build()
  }

  /**
   * Create the system_info tool definition
   */
  private def createSystemInfoTool(): McpSchema.Tool = {
    val inputSchema = new McpSchema.JsonSchema(
      "object",
      Map.empty[String, Object].asJava,
      List.empty[String].asJava,
      null, // additionalProperties
      null, // defs
      null  // definitions
    )

    McpSchema.Tool.builder()
      .name("system_info")
      .description("Returns system information about the Turnstile server")
      .inputSchema(inputSchema)
      .build()
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
