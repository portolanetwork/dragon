package app.dragon.turnstile.service

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.service.tools.{EchoTool, SystemInfoTool}
import com.typesafe.config.Config
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

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
 * Service for managing MCP tools.
 *
 * This service provides access to default (built-in) MCP tools
 * and handlers for use with MCP servers.
 *
 * Key features:
 * - Default (built-in) tools available to all users
 * - Thread-safe concurrent access
 * - Java BiFunction handlers for MCP SDK compatibility
 *
 * Example usage:
 * {{{
 * val toolsService = ToolsService.instance
 *
 * // Get all default tools
 * val tools = toolsService.getTools("user123")
 *
 * // Get tools with handlers for MCP server integration
 * val toolsWithHandlers = toolsService.getToolsWithHandlers("user123")
 * }}}
 */


object ToolsService {

  /**
   * Singleton instance of ToolsService.
   *
   * Initializes lazily on first access with:
   * - Config loaded directly from ApplicationConfig.rootConfig
   * - Global ExecutionContext for async operations
   *
   * This ensures a single shared instance across the application.
   */
  lazy val instance: ToolsService = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val config = ApplicationConfig.rootConfig
    new ToolsService(config)
  }
}


class ToolsService(config: Config)(implicit ec: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[ToolsService])

  // Default tools (built-in)
  private val defaultTools: List[McpTool] = List(
    EchoTool().tool,
    SystemInfoTool().tool
  )

  logger.info(s"ToolsService initialized with ${defaultTools.size} default tools: ${defaultTools.map(_.name).mkString(", ")}")

  /**
   * Get all tools for a user.
   * Currently returns default tools for all users.
   *
   * @param userId The user identifier
   * @return List of McpTool instances
   */
  def getTools(userId: String): List[McpTool] = {
    require(userId.nonEmpty, "userId cannot be empty")
    logger.debug(s"Getting tools for user $userId")
    defaultTools
  }

  /**
   * Get default tools available to all users.
   *
   * @return List of default tools
   */
  def getDefaultTools: List[McpTool] = defaultTools

  /**
   * Get all tools with their handlers as Java BiFunction for SDK compatibility.
   * This method converts the Scala handlers to Java BiFunction format required by the MCP SDK.
   *
   * @param userId The user identifier
   * @return List of tuples containing tool schema and Java BiFunction handler
   */
  def getToolsWithHandlers(userId: String):
    List[(McpSchema.Tool, java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult])] = {
    require(userId.nonEmpty, "userId cannot be empty")

    val userTools = getTools(userId)

    userTools.map { tool =>
      val javaHandler = new java.util.function.BiFunction[McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult] {
        override def apply(ctx: McpTransportContext, req: McpSchema.CallToolRequest): McpSchema.CallToolResult =
          tool.handler(ctx, req)
      }
      (tool.schema, javaHandler)
    }
  }
}
