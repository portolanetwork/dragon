package app.dragon.turnstile.service.tools

import app.dragon.turnstile.service.{AsyncToolHandler, McpTool, SyncToolHandler}
import app.dragon.turnstile.service.McpUtils
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

object EchoTool {
  def apply(name: String): EchoTool = new EchoTool(name)
}

class EchoTool(name: String) extends McpTool {
  override def getSchema(): McpSchema.Tool = {
    McpUtils.createToolSchemaBuilder(
      name = name,
      description = "Echoes back the provided message"
    )
      .inputSchema(
        McpUtils.createObjectSchema(
          properties = Map(
            "message" -> Map(
              "type" -> "string",
              "description" -> "The message to echo back"
            )
          ),
          required = Seq("message")
        ))
      .build()
  }

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request) => {
      val message = McpUtils.getStringArg(request, "message")
      logger.debug(s"Echo tool called with message: $message")
      Mono.just(McpUtils.createTextResult(s"Echo: $message"))
    }
  }
}
