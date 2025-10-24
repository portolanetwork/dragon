package app.dragon.turnstile.service.tools

import app.dragon.turnstile.service.{McpTool, ToolHandler}

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
class SystemInfoTool() extends McpToolProvider {

  override def tool: McpTool = {
    val schema = createToolSchemaBuilder(
      name = "system_info",
      description = "Returns system information about the Turnstile server"
    )
      .inputSchema(createObjectSchema()) // No arguments required
      .build()

    val handler: ToolHandler = (_, _) => {
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

      createTextResult(info)
    }

    McpTool(
      name = "system_info",
      description = "Returns system information",
      schema = schema,
      handler = handler
    )
  }
}

object SystemInfoTool {
  /**
   * Create a new SystemInfoTool instance
   *
   */
  def apply(): SystemInfoTool =
    new SystemInfoTool()
}
