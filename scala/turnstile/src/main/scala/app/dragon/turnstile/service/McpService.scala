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
