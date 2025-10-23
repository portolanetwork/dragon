package app.dragon.turnstile.service

import com.typesafe.config.Config
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*

/**
 * Type alias for MCP tool handler functions
 */
type ToolHandler = (McpTransportContext, McpSchema.CallToolRequest) => McpSchema.CallToolResult

/**
 * Represents an MCP tool with its definition and handler
 */
case class McpTool(
  name: String,
  description: String,
  schema: McpSchema.Tool,
  handler: ToolHandler
)

/**
 * Service layer for MCP tool definitions and handlers.
 * Provides a functional, composable approach to defining MCP tools.
 *
 * Tools are defined as a sequence of McpTool instances, making it easy
 * to add, remove, or compose tools functionally.
 */
trait McpService {
  def tools: Seq[McpTool]

  /**
   * Get all tools with their handlers as Java BiFunction for SDK compatibility
   */
  final def getToolsWithHandlers: 
  List[(McpSchema.Tool, java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult])] = {
    tools.map { tool =>
      val javaHandler = new java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult] {
        override def apply(ctx: McpTransportContext, req: McpSchema.CallToolRequest): McpSchema.CallToolResult =
          tool.handler(ctx, req)
      }
      (tool.schema, javaHandler)
    }.toList
  }
}

/**
 * Default implementation of MCP service with standard tools
 */
class DefaultMcpService(config: Config) extends McpService {
  private val logger: Logger = LoggerFactory.getLogger(classOf[DefaultMcpService])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")

  override val tools: Seq[McpTool] = Seq(
    echoTool,
    systemInfoTool
  )

  /**
   * Echo tool - echoes back the provided message
   */
  private def echoTool: McpTool = {
    val schema = McpSchema.Tool.builder()
      .name("echo")
      .description("Echoes back the provided message")
      .inputSchema(createObjectSchema(
        properties = Map(
          "message" -> Map(
            "type" -> "string",
            "description" -> "The message to echo back"
          )
        ),
        required = Seq("message")
      ))
      .build()

    val handler: ToolHandler = (_, request) => {
      val message = Option(request.arguments())
        .flatMap(args => Option(args.get("message")))
        .map(_.toString)
        .getOrElse("")

      logger.debug(s"Echo tool called with message: $message")

      createTextResult(s"Echo: $message")
    }

    McpTool("echo", "Echoes back messages", schema, handler)
  }

  /**
   * System info tool - returns detailed system information
   */
  private def systemInfoTool: McpTool = {
    val schema = McpSchema.Tool.builder()
      .name("system_info")
      .description("Returns system information about the Turnstile server")
      .inputSchema(createObjectSchema())
      .build()

    val handler: ToolHandler = (_, _) => {
      val runtime = Runtime.getRuntime
      val totalMemory = runtime.totalMemory()
      val freeMemory = runtime.freeMemory()
      val usedMemory = totalMemory - freeMemory
      val maxMemory = runtime.maxMemory()

      val info =
        s"""System Information:
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

      createTextResult(info)
    }

    McpTool("system_info", "Returns system information", schema, handler)
  }

  /**
   * Helper to create JSON schema for object types
   */
  private def createObjectSchema(
    properties: Map[String, Map[String, String]] = Map.empty,
    required: Seq[String] = Seq.empty
  ): McpSchema.JsonSchema = {
    val javaProperties = properties.map { case (key, value) =>
      key -> value.asJava.asInstanceOf[Object]
    }.asJava

    new McpSchema.JsonSchema(
      "object",
      javaProperties,
      required.asJava,
      null, // additionalProperties
      null, // defs
      null  // definitions
    )
  }

  /**
   * Helper to create a text-based tool result
   */
  private def createTextResult(text: String, isError: Boolean = false): McpSchema.CallToolResult = {
    val content: java.util.List[McpSchema.Content] = List(
      new McpSchema.TextContent(text)
    ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

    McpSchema.CallToolResult.builder()
      .content(content)
      .isError(isError)
      .build()
  }
}

object McpService {
  /**
   * Create the default MCP service implementation
   */
  def apply(config: Config): McpService = new DefaultMcpService(config)

  /**
   * Create a custom MCP service with additional tools
   */
  def withTools(config: Config, additionalTools: McpTool*): McpService = {
    val baseService = new DefaultMcpService(config)
    new McpService {
      override val tools: Seq[McpTool] = baseService.tools ++ additionalTools
    }
  }
}
