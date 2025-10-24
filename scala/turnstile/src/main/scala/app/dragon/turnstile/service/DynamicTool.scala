package app.dragon.turnstile.service

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
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
 * @param name Tool name (must be unique per user)
 * @param description Tool description
 * @param schemaJson JSON schema for input parameters (as JSON string)
 * @param handler Handler function for the tool
 * @param isDefault Whether this is a default (non-replaceable) tool
 */
case class DynamicTool(
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
  private val objectMapper = new ObjectMapper()
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
    parseJsonSchema(dynamicTool.schemaJson).map { inputSchema =>
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
   * Parse JSON schema string into McpSchema.JsonSchema
   *
   * @param schemaJson JSON schema as string
   * @return Try[McpSchema.JsonSchema]
   */
  def parseJsonSchema(schemaJson: String): Try[McpSchema.JsonSchema] = Try {
    val jsonNode = objectMapper.readTree(schemaJson)

    val schemaType = Option(jsonNode.get("type"))
      .map(_.asText())
      .getOrElse("object")

    val properties = Option(jsonNode.get("properties"))
      .map { propsNode =>
        val fields = propsNode.fields().asScala
        fields.map { entry =>
          val key = entry.getKey
          val value = entry.getValue
          // Convert JsonNode to Map[String, Any] for Java interop
          val propMap = jsonNodeToMap(value)
          key -> propMap.asJava.asInstanceOf[Object]
        }.toMap.asJava
      }
      .getOrElse(java.util.Collections.emptyMap())

    val required = Option(jsonNode.get("required"))
      .map { reqNode =>
        reqNode.elements().asScala.map(_.asText()).toSeq.asJava
      }
      .getOrElse(java.util.Collections.emptyList())

    new McpSchema.JsonSchema(
      schemaType,
      properties,
      required,
      null, // additionalProperties
      null, // defs
      null  // definitions
    )
  }

  /**
   * Convert JsonNode to Map[String, Any] for Java interop
   */
  private def jsonNodeToMap(node: JsonNode): Map[String, Any] = {
    if (node.isObject) {
      node.fields().asScala.map { entry =>
        entry.getKey -> (entry.getValue match {
          case v if v.isTextual => v.asText()
          case v if v.isNumber => v.asDouble()
          case v if v.isBoolean => v.asBoolean()
          case v if v.isObject => jsonNodeToMap(v)
          case v if v.isArray => v.elements().asScala.map {
            case elem if elem.isTextual => elem.asText()
            case elem if elem.isNumber => elem.asDouble()
            case elem if elem.isBoolean => elem.asBoolean()
            case elem => jsonNodeToMap(elem)
          }.toList
          case _ => null
        })
      }.toMap
    } else {
      Map.empty
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
      parseJsonSchema(tool.schemaJson) match {
        case Success(_) => Right(tool)
        case Failure(ex) => Left(s"Invalid JSON schema: ${ex.getMessage}")
      }
    }
  }
}
