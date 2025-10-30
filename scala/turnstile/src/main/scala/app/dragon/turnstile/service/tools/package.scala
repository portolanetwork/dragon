package app.dragon.turnstile.service

/**
 * MCP Tools Package
 *
 * This package contains individual MCP tool implementations, each in its own file
 * extending the McpTool trait.
 *
 * ## Architecture
 *
 * Each tool is:
 * - Self-contained in its own file (typically as an object extending McpTool)
 * - Independently testable
 * - Composable with other tools
 * - Documented with usage examples
 *
 * ## Available Tools
 *
 * - **EchoTool**: Echo messages back (useful for testing)
 * - **SystemInfoTool**: Return server and system information
 * - **ActorTool**: Demonstrates streaming/async capabilities with progress notifications
 *
 * ## Creating New Tools
 *
 * To create a new tool:
 *
 * 1. Create a new file in this package (e.g., MyCustomTool.scala)
 * 2. Extend McpTool trait
 * 3. Implement `getSchema()` and `getAsyncHandler()` methods
 * 4. Use helper methods from McpUtils
 *
 * Example:
 * {{{
 * package app.dragon.turnstile.service.tools
 *
 * import app.dragon.turnstile.service.{AsyncToolHandler, McpTool}
 * import app.dragon.turnstile.service.McpUtils.*
 * import io.modelcontextprotocol.spec.McpSchema
 * import reactor.core.publisher.Mono
 *
 * object MyCustomTool extends McpTool {
 *   override def getSchema(): McpSchema.Tool = {
 *     createToolSchemaBuilder("my_tool", "Description")
 *       .inputSchema(createObjectSchema(
 *         properties = Map(
 *           "input" -> Map("type" -> "string", "description" -> "Input parameter")
 *         ),
 *         required = Seq("input")
 *       ))
 *       .build()
 *   }
 *
 *   override def getAsyncHandler(): AsyncToolHandler = {
 *     (exchange, request) => {
 *       val input = getStringArg(request, "input")
 *       logger.debug(s"Tool called with: $input")
 *       Mono.just(createTextResult(s"Result: $input"))
 *     }
 *   }
 * }
 * }}}
 *
 * 5. Register the tool in ToolsService:
 * {{{
 * private val defaultTools: List[McpTool] = List(
 *   EchoTool,
 *   SystemInfoTool,
 *   MyCustomTool  // Add your tool here
 * )
 * }}}
 *
 * ## Helper Methods
 *
 * McpUtils provides these helper methods:
 *
 * - `createObjectSchema()`: Create JSON schema for tool input
 * - `createToolSchemaBuilder()`: Create tool schema builder
 * - `createTextResult()`: Create text-based tool result
 * - `getStringArg()`: Extract string argument from request
 * - `getIntArg()`: Extract integer argument from request
 * - `getBooleanArg()`: Extract boolean argument from request
 *
 * Import them with: `import app.dragon.turnstile.service.McpUtils.*`
 *
 * ## Testing
 *
 * Each tool can be tested independently:
 * {{{
 * class MyCustomToolSpec extends AnyFlatSpec with Matchers {
 *   "MyCustomTool" should "return expected result" in {
 *     val schema = MyCustomTool.getSchema()
 *     val handler = MyCustomTool.getAsyncHandler()
 *     val request = createTestRequest("input" -> "test")
 *     val result = handler(exchange, request).block()
 *     // Assertions...
 *   }
 * }
 * }}}
 */
package object tools {
  // This package object provides documentation and can be extended with
  // package-level utilities if needed in the future
}
