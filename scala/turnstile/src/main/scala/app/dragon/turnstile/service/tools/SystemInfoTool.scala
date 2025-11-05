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

package app.dragon.turnstile.service.tools

import app.dragon.turnstile.service.{AsyncToolHandler, McpTool, McpUtils, SyncToolHandler}
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono

/**
 * System info tool - returns detailed system information about the server.
 *
 * This tool provides:
 * - Server name and version
 * - Java runtime information (version, vendor)
 * - Operating system details (name, architecture, version)
 * - Hardware information (processor count)
 * - Memory statistics (total, used, free, max)
 *
 * This is useful for:
 * - Monitoring server health
 * - Debugging deployment issues
 * - Checking resource availability
 * - Verifying runtime environment
 *
 * Example usage:
 * {{{
 * {
 *   "name": "system_info",
 *   "arguments": {}
 * }
 * }}}
 *
 * Returns: Formatted system information text
 *
 * @param serverName The name of the server (from config)
 * @param serverVersion The version of the server (from config)
 */
object SystemInfoTool extends McpTool {
  override def getSchema(): McpSchema.Tool = {
    McpUtils.createToolSchemaBuilder(
      name = "system_info",
      description = "Returns system information about the Turnstile server"
    )
      .inputSchema(McpUtils.createObjectSchema()) // No arguments required
      .build()
  }

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request) => {
      Mono.fromSupplier(() => {
        val runtime = Runtime.getRuntime
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()

        val info =
          s"""System Information:
             |Java Version: ${System.getProperty("java.version")}
             |Java Vendor: ${System.getProperty("java.vendor")}
             |OS Name: ${System.getProperty("os.name")}
             |OS Architecture: ${System.getProperty("os.arch")}
             |OS Version: ${System.getProperty("os.version")}
             |Available Processors: ${runtime.availableProcessors()}
             |Total Memory: ${totalMemory / 1024 / 1024} MB
             |Used Memory: ${usedMemory / 1024 / 1024} MB
             |Free Memory: ${freeMemory / 1024 / 1024} MB
             |Max Memory: ${maxMemory / 1024 / 1024} MB
             |""".stripMargin

        logger.debug("System info tool called")

        McpUtils.createTextResult(info)
      })
    }
  }

}
