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

package app.dragon.turnstile.mcp_tools.impl

import app.dragon.turnstile.mcp_tools.{AsyncToolHandler, McpTool, McpUtils}
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

object Echo {
  def apply(name: String): Echo = new Echo(name)
}

class Echo(
  name: String
) extends McpTool {
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
