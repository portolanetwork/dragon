package app.dragon.turnstile.service.tools

import app.dragon.turnstile.service.{McpTool, SyncToolHandler}
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono

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

    val handler: SyncToolHandler = (_, request) => {
      val message = getStringArg(request, "message")

      logger.debug(s"Echo tool called with message: $message")

      createTextResult(s"Echo: $message")
    }

    // Async handler wraps the sync handler for compatibility
    val asyncHandler: (McpAsyncServerExchange, McpSchema.CallToolRequest) => Mono[McpSchema.CallToolResult] =
      (exchange, request) => Mono.fromSupplier(() => handler(exchange.transportContext(), request))

    McpTool(
      name = "echo",
      description = "Echoes back messages",
      schema = schema,
      syncHandler = handler,
      asyncHandler = asyncHandler
    )
  }
}

object EchoTool {
  /**
   * Create a new EchoTool instance
   */
  def apply(): EchoTool = new EchoTool()
}
