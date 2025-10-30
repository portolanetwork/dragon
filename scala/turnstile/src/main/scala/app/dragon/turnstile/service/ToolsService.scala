package app.dragon.turnstile.service

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.service.tools.{ActorTool, EchoTool, SystemInfoTool}
import com.typesafe.config.Config
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.{McpAsyncServerExchange, McpServerFeatures}
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FunctionConverters.enrichAsJavaBiFunction
import scala.util.{Failure, Success, Try}

/**
 * Type alias for MCP tool handler functions
 */
type SyncToolHandler = (McpTransportContext, McpSchema.CallToolRequest) => McpSchema.CallToolResult
type AsyncToolHandler = (McpAsyncServerExchange, McpSchema.CallToolRequest) => reactor.core.publisher.Mono[McpSchema.CallToolResult]

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
    new ToolsService()
  }
}


class ToolsService()(implicit ec: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[ToolsService])

  // Default tools (built-in)
  private val defaultTools: List[McpTool] = List(
    EchoTool("echo1"),
    EchoTool("echo2"),
    SystemInfoTool,
    ActorTool
  )

  logger.info(s"ToolsService initialized with ${defaultTools.size} default tools: ${defaultTools.map(_.getName()).mkString(", ")}")

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

  def getAsyncToolsSpec(
    userId: String
  ): List[McpServerFeatures.AsyncToolSpecification] = {
    require(userId.nonEmpty, "userId cannot be empty")
    val userTools = getTools(userId)
    userTools.map { tool =>
      McpServerFeatures.AsyncToolSpecification.builder()
        .tool(tool.getSchema())
        .callHandler(tool.getAsyncHandler().asJava)
        .build()
    }
  }
}
