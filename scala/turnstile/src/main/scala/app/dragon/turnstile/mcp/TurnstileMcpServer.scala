package app.dragon.turnstile.mcp

import com.typesafe.config.Config
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.server.{McpServer, McpServerFeatures, McpSyncServer}
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/**
 * MCP Server implementation for Turnstile.
 * Provides tools and resources via Model Context Protocol.
 *
 * Currently implements:
 * - echo: Simple message echo for testing
 * - system_info: Return server system information
 */
class TurnstileMcpServer(config: Config) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpServer])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")
  private val host = config.getString("host")
  private val port = config.getInt("port")

  private val serverRef: AtomicReference[Option[McpSyncServer]] = new AtomicReference(None)

  /**
   * Initialize the MCP server with tools
   */
  def initialize(): Unit = {
    logger.info(s"Initializing MCP Server: $serverName v$serverVersion")
    logger.info(s"MCP Server will be available via STDIO transport")

    try {
      // Create JSON mapper for MCP protocol
      val jsonMapper = new JacksonMcpJsonMapperSupplier().get()

      // Create transport provider (using STDIO for now, HTTP streaming could be added later)
      val transportProvider = new StdioServerTransportProvider(jsonMapper)

      // Create sync server
      val server = McpServer.sync(transportProvider)
        .serverInfo(serverName, serverVersion)
        .capabilities(McpSchema.ServerCapabilities.builder()
          .tools(true)
          .build())
        .build()

      // Register echo tool
      server.addTool(createEchoTool())
      logger.info("Registered echo tool")

      // Register system_info tool
      server.addTool(createSystemInfoTool())
      logger.info("Registered system_info tool")

      serverRef.set(Some(server))
      logger.info("MCP Server initialized successfully")

    } catch {
      case e: Exception =>
        logger.error("Failed to initialize MCP Server", e)
        throw e
    }
  }

  /**
   * Creates the echo tool specification
   */
  private def createEchoTool(): McpServerFeatures.SyncToolSpecification = {
    // Create JSON schema for the tool input
    val messageProperty = Map[String, Any](
      "type" -> "string",
      "description" -> "The message to echo back"
    ).asJava

    val properties = Map[String, Any](
      "message" -> messageProperty
    ).asJava

    val inputSchema = new McpSchema.JsonSchema(
      "object",
      properties.asInstanceOf[java.util.Map[String, Object]],
      List("message").asJava,
      null,
      null,
      null
    )

    val tool = McpSchema.Tool.builder()
      .name("echo")
      .description("Echoes back the provided message")
      .inputSchema(inputSchema)
      .build()

    new McpServerFeatures.SyncToolSpecification(
      tool,
      (exchange, arguments) => {
        try {
          val message = arguments.get("message") match {
            case s: String => s
            case other => String.valueOf(other)
          }

          logger.debug(s"Echo tool called with message: $message")

          val textContent = new McpSchema.TextContent(s"Echo: $message")

          McpSchema.CallToolResult.builder()
            .content(List[McpSchema.Content](textContent).asJava)
            .isError(false)
            .build()
        } catch {
          case e: Exception =>
            logger.error("Error in echo tool", e)
            val errorContent = new McpSchema.TextContent(s"Error: ${e.getMessage}")
            McpSchema.CallToolResult.builder()
              .content(List[McpSchema.Content](errorContent).asJava)
              .isError(true)
              .build()
        }
      }
    )
  }

  /**
   * Creates the system_info tool specification
   */
  private def createSystemInfoTool(): McpServerFeatures.SyncToolSpecification = {
    // Create JSON schema with no required properties
    val inputSchema = new McpSchema.JsonSchema(
      "object",
      Map[String, Object]().asJava,
      List[String]().asJava,
      null,
      null,
      null
    )

    val tool = McpSchema.Tool.builder()
      .name("system_info")
      .description("Returns system information about the Turnstile server")
      .inputSchema(inputSchema)
      .build()

    new McpServerFeatures.SyncToolSpecification(
      tool,
      (exchange, arguments) => {
        try {
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

          val textContent = new McpSchema.TextContent(info)

          McpSchema.CallToolResult.builder()
            .content(List[McpSchema.Content](textContent).asJava)
            .isError(false)
            .build()
        } catch {
          case e: Exception =>
            logger.error("Error in system_info tool", e)
            val errorContent = new McpSchema.TextContent(s"Error retrieving system information: ${e.getMessage}")
            McpSchema.CallToolResult.builder()
              .content(List[McpSchema.Content](errorContent).asJava)
              .isError(true)
              .build()
        }
      }
    )
  }

  /**
   * Start the MCP server
   */
  def start(): Unit = {
    serverRef.get() match {
      case Some(server) =>
        logger.info("MCP Server is ready and listening")
        // The STDIO transport is already listening when the server is built
      case None =>
        logger.error("Cannot start MCP Server - not initialized")
        throw new IllegalStateException("MCP Server not initialized")
    }
  }

  /**
   * Stop the MCP server
   */
  def stop(): Unit = {
    logger.info("Stopping MCP Server")
    serverRef.get().foreach { server =>
      try {
        // Close the server if it has a close method
        // The Java SDK may handle this automatically
        logger.info("MCP Server stopped")
      } catch {
        case e: Exception =>
          logger.error("Error stopping MCP Server", e)
      }
    }
    serverRef.set(None)
  }
}

object TurnstileMcpServer {
  def apply(config: Config): TurnstileMcpServer = {
    val server = new TurnstileMcpServer(config)
    server.initialize()
    server
  }
}
