package app.dragon.turnstile.service

import com.typesafe.config.Config
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*

/**
 * Service layer for MCP tool definitions and handlers.
 * Abstracts tool registration and execution logic from the MCP server transport.
 *
 * This service:
 * - Defines MCP tool schemas
 * - Implements tool execution handlers
 * - Provides clean separation between business logic and protocol handling
 */
class McpService(config: Config) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[McpService])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")

  /**
   * Get all tool definitions with their handlers
   */
  def getToolsWithHandlers: List[(McpSchema.Tool, java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult])] = {
    List(
      (createEchoTool(), echoToolHandler),
      (createSystemInfoTool(), systemInfoToolHandler)
    )
  }

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
   * Echo tool handler - echoes back the provided message
   */
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
   * System info tool handler - returns detailed system information
   */
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
}

object McpService {
  def apply(config: Config): McpService = new McpService(config)
}
