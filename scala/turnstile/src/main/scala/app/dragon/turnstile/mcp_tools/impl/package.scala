/*
 * Copyright 2025 Sami Malik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: Sami Malik (sami.malik [at] portolanetwork.io)
 */

package app.dragon.turnstile.mcp_tools

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
 * - **StreamingDemoTool**: Demonstrates streaming/async capabilities with progress notifications
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
 * package app.dragon.turnstile.mcp_tools.impl
 *
 * import app.dragon.turnstile.mcp_tools.McpTool
 * import app.dragon.turnstile.mcp_tools.McpUtils.*
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
 * Import them with: `import app.dragon.turnstile.mcp_tools.McpUtils.*`
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
package object impl {
  // This package object provides documentation and can be extended with
  // package-level utilities if needed in the future
}
