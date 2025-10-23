package app.dragon.turnstile.service.tools

import app.dragon.turnstile.service.{McpTool, ToolHandler}

/**
 * Echo tool - echoes back the provided message.
 *
 * This tool is useful for:
 * - Testing MCP connectivity
 * - Verifying message passing
 * - Debugging MCP client-server communication
 *
 * Example usage:
 * {{{
 * {
 *   "name": "echo",
 *   "arguments": {
 *     "message": "Hello, World!"
 *   }
 * }
 * }}}
 *
 * Returns: "Echo: Hello, World!"
 */
class EchoTool extends McpToolProvider {

  override def tool: McpTool = {
    val schema = createToolSchemaBuilder(
      name = "echo",
      description = "Echoes back the provided message"
    )
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
      val message = getStringArg(request, "message")

      logger.debug(s"Echo tool called with message: $message")

      createTextResult(s"Echo: $message")
    }

    McpTool(
      name = "echo",
      description = "Echoes back messages",
      schema = schema,
      handler = handler
    )
  }
}

object EchoTool {
  /**
   * Create a new EchoTool instance
   */
  def apply(): EchoTool = new EchoTool()
}
