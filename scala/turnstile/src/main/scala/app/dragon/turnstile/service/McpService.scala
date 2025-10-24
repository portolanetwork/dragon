package app.dragon.turnstile.service

import app.dragon.turnstile.service.tools.{EchoTool, SystemInfoTool}
import com.typesafe.config.Config
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

/**
 * Type alias for MCP tool handler functions
 */
type ToolHandler = (McpTransportContext, McpSchema.CallToolRequest) => McpSchema.CallToolResult

/**
 * Represents an MCP tool with its definition and handler
 */
case class McpTool(
  name: String,
  description: String,
  schema: McpSchema.Tool,
  handler: ToolHandler
)

/**
 * Service layer for MCP tool definitions and handlers.
 * Provides a functional, composable approach to defining MCP tools.
 *
 * Tools are defined as a sequence of McpTool instances, making it easy
 * to add, remove, or compose tools functionally.
 */
trait McpService {
  def tools: Seq[McpTool]

  /**
   * Get all tools with their handlers as Java BiFunction for SDK compatibility
   */
  final def getToolsWithHandlers:
  List[(McpSchema.Tool, java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult])] = {
    tools.map { tool =>
      val javaHandler = new java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult] {
        override def apply(ctx: McpTransportContext, req: McpSchema.CallToolRequest): McpSchema.CallToolResult =
          tool.handler(ctx, req)
      }
      (tool.schema, javaHandler)
    }.toList
  }
}

/**
 * Default implementation of MCP service with standard tools.
 *
 * This service now uses the refactored tool architecture where each tool
 * is defined in its own file in the `app.dragon.turnstile.service.tools` package.
 *
 * Standard tools:
 * - EchoTool: Echo messages back for testing
 * - SystemInfoTool: Return server and system information
 *
 * To add new tools:
 * 1. Create a new class extending McpToolProvider in the tools package
 * 2. Add the tool instance to the `tools` sequence below
 

class DefaultMcpService(config: Config) extends McpService {
  private val logger: Logger = LoggerFactory.getLogger(classOf[ DefaultMcpService])

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")

  override val tools: Seq[McpTool] = Seq(
    EchoTool().tool,
    SystemInfoTool(serverName, serverVersion).tool
  )
}


object McpService {
  def apply(config: Config): McpService = new DefaultMcpService(config)
  def withTools(config: Config, additionalTools: McpTool*): McpService = {
    val baseService = new DefaultMcpService(config)
    new McpService {
      override val tools: Seq[McpTool] = baseService.tools ++ additionalTools
    }
  }
}
*/
