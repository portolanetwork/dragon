package app.dragon.turnstile.service

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.service.tools.{EchoTool, SystemInfoTool}
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * Service for managing MCP tools.
 *
 * This service provides access to default (built-in) MCP tools
 * and creates McpService instances for use with MCP servers.
 *
 * Key features:
 * - Default (built-in) tools available to all users
 * - Thread-safe concurrent access
 * - McpService creation for MCP server integration
 *
 * Example usage:
 * {{{
 * val toolsService = ToolsService.instance
 *
 * // Get all default tools
 * val tools = toolsService.getTools("user123")
 *
 * // Create an McpService instance for a user
 * val mcpServiceFut = toolsService.createServiceForUser("user123")
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

  /**
   * Legacy factory method for backwards compatibility.
   * Now returns the singleton instance, ignoring the provided config.
   *
   * @deprecated Use ToolsService.instance instead
   */
  @deprecated("Use ToolsService.instance instead", "1.0.0")
  def apply(config: Config)(implicit ec: ExecutionContext): ToolsService = instance
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
   * Create an McpService instance for a specific user.
   *
   * Currently returns default tools for all users.
   *
   * @param userId The user identifier
   * @return Future[McpService] instance with user's tools
   */
  def createServiceForUser(userId: String): Future[McpService] = {
    require(userId.nonEmpty, "userId cannot be empty")
    Future.successful(new McpService {
      override val tools: Seq[McpTool] = defaultTools
    })
  }
}
