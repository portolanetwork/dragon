package app.dragon.turnstile.service

/**
 * MCP Tools Package
 *
 * This package contains individual MCP tool implementations, each in its own file
 * extending the McpToolProvider trait.
 *
 * ## Architecture
 *
 * Each tool is:
 * - Self-contained in its own file
 * - Independently testable
 * - Composable with other tools
 * - Documented with usage examples
 *
 * ## Available Tools
 *
 * - **EchoTool**: Echo messages back (useful for testing)
 * - **SystemInfoTool**: Return server and system information
 *
 * ## Creating New Tools
 *
 * To create a new tool:
 *
 * 1. Create a new file in this package (e.g., MyCustomTool.scala)
 * 2. Extend McpToolProvider
 * 3. Implement the `tool` method
 * 4. Use helper methods from McpToolProvider
 *
 * Example:
 * {{{
 * package app.dragon.turnstile.service.tools
 *
 * import app.dragon.turnstile.service.{McpTool, ToolHandler}
 *
 * class MyCustomTool extends McpToolProvider {
 *   override def tool: McpTool = {
 *     val schema = createToolSchemaBuilder("my_tool", "Description")
 *       .inputSchema(createObjectSchema(
 *         properties = Map(
 *           "input" -> Map("type" -> "string", "description" -> "Input parameter")
 *         ),
 *         required = Seq("input")
 *       ))
 *       .build()
 *
 *     val handler: ToolHandler = (ctx, req) => {
 *       val input = getStringArg(req, "input")
 *       logger.debug(s"Tool called with: $input")
 *       createTextResult(s"Result: $input")
 *     }
 *
 *     McpTool("my_tool", "Description", schema, handler)
 *   }
 * }
 *
 * object MyCustomTool {
 *   def apply(): MyCustomTool = new MyCustomTool()
 * }
 * }}}
 *
 * 5. Register the tool in DefaultMcpService:
 * {{{
 * override val tools: Seq[McpTool] = Seq(
 *   EchoTool().tool,
 *   SystemInfoTool(serverName, serverVersion).tool,
 *   MyCustomTool().tool  // Add your tool here
 * )
 * }}}
 *
 * ## Helper Methods
 *
 * McpToolProvider provides these helper methods:
 *
 * - `createObjectSchema()`: Create JSON schema for tool input
 * - `createToolSchemaBuilder()`: Create tool schema builder
 * - `createTextResult()`: Create text-based tool result
 * - `getStringArg()`: Extract string argument from request
 * - `getIntArg()`: Extract integer argument from request
 * - `getBooleanArg()`: Extract boolean argument from request
 * - `logger`: SLF4J logger instance
 *
 * ## Testing
 *
 * Each tool can be tested independently:
 * {{{
 * class MyCustomToolSpec extends AnyFlatSpec with Matchers {
 *   "MyCustomTool" should "return expected result" in {
 *     val tool = MyCustomTool().tool
 *     val request = createTestRequest("input" -> "test")
 *     val result = tool.handler(McpTransportContext.EMPTY, request)
 *     // Assertions...
 *   }
 * }
 * }}}
 */
package object tools {
  // This package object provides documentation and can be extended with
  // package-level utilities if needed in the future
}
