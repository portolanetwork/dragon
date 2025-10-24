package app.dragon.turnstile.service

import app.dragon.turnstile.utils.JsonUtils
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * Represents a dynamically registered tool with JSON schema.
 *
 * Dynamic tools allow runtime tool registration per user, with schemas
 * defined in JSON format and handlers that can be interpreted or scripted.
 *
 * @param id Database ID (None for new tools, Some for persisted tools)
 * @param name Tool name (must be unique per user)
 * @param description Tool description
 * @param schemaJson JSON schema for input parameters (as JSON string)
 * @param handler Handler function for the tool
 * @param isDefault Whether this is a default (non-replaceable) tool
 */
case class DynamicTool(
  id: Option[Long] = None,
  name: String,
  description: String,
  schemaJson: String,
  handler: ToolHandler,
  isDefault: Boolean = false
)

/**
 * Companion object for DynamicTool with factory methods and utilities
 */
object DynamicTool {
  private val logger: Logger = LoggerFactory.getLogger(DynamicTool.getClass)
  private val mcpJsonMapper = McpJsonMapper.getDefault

  /**
   * Create a DynamicTool from an McpTool (for converting default tools)
   *
   * @param mcpTool The MCP tool to convert
   * @param isDefault Whether this is a default tool
   * @return DynamicTool instance
   */
  def fromMcpTool(mcpTool: McpTool, isDefault: Boolean = false): DynamicTool = {
    val schemaJson = mcpJsonMapper.writeValueAsString(mcpTool.schema.inputSchema())
    DynamicTool(
      name = mcpTool.name,
      description = mcpTool.description,
      schemaJson = schemaJson,
      handler = mcpTool.handler,
      isDefault = isDefault
    )
  }

  /**
   * Convert DynamicTool to McpTool for use with MCP SDK
   *
   * @param dynamicTool The dynamic tool to convert
   * @return Try[McpTool] - Success with McpTool or Failure with error
   */
  def toMcpTool(dynamicTool: DynamicTool): Try[McpTool] = {
    JsonUtils.parseJsonSchema(dynamicTool.schemaJson).map { inputSchema =>
      val schema = McpSchema.Tool.builder()
        .name(dynamicTool.name)
        .description(dynamicTool.description)
        .inputSchema(inputSchema)
        .build()

      McpTool(
        name = dynamicTool.name,
        description = dynamicTool.description,
        schema = schema,
        handler = dynamicTool.handler
      )
    }
  }

  /**
   * Create a simple text response handler (useful for testing)
   *
   * @param responseText The text to return
   * @return ToolHandler
   */
  def simpleTextHandler(responseText: String): ToolHandler = (_, _) => {
    val content: java.util.List[McpSchema.Content] = List(
      new McpSchema.TextContent(responseText)
    ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

    McpSchema.CallToolResult.builder()
      .content(content)
      .isError(false)
      .build()
  }

  /**
   * Create a handler that echoes arguments back (useful for testing)
   *
   * @return ToolHandler
   */
  def echoArgsHandler: ToolHandler = (_, request) => {
    val args = Option(request.arguments())
      .map { argsMap =>
        argsMap.asScala.map { case (k, v) => s"$k: $v" }.mkString("\n")
      }
      .getOrElse("No arguments provided")

    val content: java.util.List[McpSchema.Content] = List(
      new McpSchema.TextContent(args)
    ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

    McpSchema.CallToolResult.builder()
      .content(content)
      .isError(false)
      .build()
  }

  /**
   * Validate a tool definition
   *
   * @param tool The tool to validate
   * @return Either error message or success
   */
  def validate(tool: DynamicTool): Either[String, DynamicTool] = {
    if (tool.name.isEmpty) {
      Left("Tool name cannot be empty")
    } else if (tool.description.isEmpty) {
      Left("Tool description cannot be empty")
    } else {
      JsonUtils.parseJsonSchema(tool.schemaJson) match {
        case Success(_) => Right(tool)
        case Failure(ex) => Left(s"Invalid JSON schema: ${ex.getMessage}")
      }
    }
  }
}
