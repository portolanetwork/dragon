package app.dragon.turnstile.service.tools

import app.dragon.turnstile.service.{McpTool, ToolHandler}
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*

/**
 * Base trait for MCP tool providers.
 *
 * Tool providers encapsulate the definition and implementation of individual MCP tools,
 * promoting separation of concerns and making tools independently testable and composable.
 *
 * Each tool provider should:
 * 1. Define the tool schema (name, description, input schema)
 * 2. Implement the handler logic
 * 3. Return an McpTool instance via the `tool` method
 *
 * Example:
 * {{{
 * class MyCustomTool extends McpToolProvider {
 *   override def tool: McpTool = {
 *     val schema = createToolSchema("my_tool", "Description")
 *     val handler: ToolHandler = (ctx, req) => createTextResult("Result")
 *     McpTool("my_tool", "Description", schema, handler)
 *   }
 * }
 * }}}
 */
trait McpToolProvider {
  /**
   * Logger instance for the tool provider
   */
  protected val logger: Logger = LoggerFactory.getLogger(this.getClass)

  /**
   * Returns the MCP tool definition and handler
   */
  def tool: McpTool

  /**
   * Helper to create JSON schema for object types
   *
   * @param properties Map of property name to property definition (type, description, etc.)
   * @param required Sequence of required property names
   * @return JsonSchema instance
   */
  protected def createObjectSchema(
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
   *
   * @param text The text content to return
   * @param isError Whether this is an error result
   * @return CallToolResult instance
   */
  protected def createTextResult(text: String, isError: Boolean = false): McpSchema.CallToolResult = {
    val content: java.util.List[McpSchema.Content] = List(
      new McpSchema.TextContent(text)
    ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

    McpSchema.CallToolResult.builder()
      .content(content)
      .isError(isError)
      .build()
  }

  /**
   * Helper to create a tool schema builder with common settings
   *
   * @param name Tool name
   * @param description Tool description
   * @return Tool.Builder instance ready to have inputSchema set and build() called
   */
  protected def createToolSchemaBuilder(name: String, description: String): McpSchema.Tool.Builder = {
    McpSchema.Tool.builder()
      .name(name)
      .description(description)
  }

  /**
   * Helper to extract a string argument from the request
   *
   * @param request The CallToolRequest
   * @param argName The argument name to extract
   * @param default Default value if argument is missing
   * @return The argument value or default
   */
  protected def getStringArg(
    request: McpSchema.CallToolRequest,
    argName: String,
    default: String = ""
  ): String = {
    Option(request.arguments())
      .flatMap(args => Option(args.get(argName)))
      .map(_.toString)
      .getOrElse(default)
  }

  /**
   * Helper to extract an integer argument from the request
   *
   * @param request The CallToolRequest
   * @param argName The argument name to extract
   * @param default Default value if argument is missing or invalid
   * @return The argument value or default
   */
  protected def getIntArg(
    request: McpSchema.CallToolRequest,
    argName: String,
    default: Int = 0
  ): Int = {
    Option(request.arguments())
      .flatMap(args => Option(args.get(argName)))
      .flatMap(v => scala.util.Try(v.toString.toInt).toOption)
      .getOrElse(default)
  }

  /**
   * Helper to extract a boolean argument from the request
   *
   * @param request The CallToolRequest
   * @param argName The argument name to extract
   * @param default Default value if argument is missing or invalid
   * @return The argument value or default
   */
  protected def getBooleanArg(
    request: McpSchema.CallToolRequest,
    argName: String,
    default: Boolean = false
  ): Boolean = {
    Option(request.arguments())
      .flatMap(args => Option(args.get(argName)))
      .flatMap(v => scala.util.Try(v.toString.toBoolean).toOption)
      .getOrElse(default)
  }
}
