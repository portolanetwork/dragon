package app.dragon.turnstile.service

import app.dragon.turnstile.db.ToolsDAO
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * Service for managing dynamic tool registration per user with database persistence.
 *
 * This service allows users to register custom tools at runtime while preserving
 * default system tools. Each user has their own set of custom tools that are merged
 * with the default tools when requested.
 *
 * Key features:
 * - Per-user tool registration
 * - Database persistence with PostgreSQL
 * - Preserves default (built-in) tools
 * - Thread-safe concurrent access
 * - Tool validation
 * - JSON schema support for tool definitions
 * - In-memory cache for performance
 *
 * Note: Handlers are not persisted to the database. When loading tools from the database,
 * you must associate them with handlers using the tool registry or custom handler maps.
 *
 * Example usage:
 * {{{
 * val toolsService = ToolsService(config)
 *
 * // Define a custom tool with JSON schema
 * val customTool = DynamicTool(
 *   name = "custom_calculator",
 *   description = "Custom calculator for user",
 *   schemaJson = """{"type": "object", "properties": {"x": {"type": "number"}}}""",
 *   handler = (ctx, req) => { /* implementation */ }
 * )
 *
 * // Update tools for a user (persisted to database)
 * toolsService.updateTools("user123", List(customTool))
 *
 * // Get all tools for a user (default + custom from database)
 * val allTools = toolsService.getToolsForUser("user123")
 * }}}
 */
class ToolsService(config: Config)(implicit ec: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[ToolsService])

  // Database timeout for sync operations
  private val dbTimeout = 10.seconds

  // Default tools (from configuration)
  private val defaultService = new DefaultMcpService(config)
  private val defaultTools: List[DynamicTool] = defaultService.tools
    .map(tool => DynamicTool.fromMcpTool(tool, isDefault = true))
    .toList

  // User-specific custom tools - in-memory cache (userId -> List[DynamicTool])
  // This is a cache layer on top of database for performance
  private val userToolsCache = new ConcurrentHashMap[String, List[DynamicTool]]()

  // Handler registry: Maps tool names to their handlers
  // Since handlers are not persisted, we need a registry to associate them
  private val handlerRegistry = new ConcurrentHashMap[String, ToolHandler]()

  // Database DAO
  private val toolsDAO = ToolsDAO(config)

  logger.info(s"ToolsService initialized with ${defaultTools.size} default tools: ${defaultTools.map(_.name).mkString(", ")}")
  logger.info("Database persistence enabled for custom tools")

  /**
   * Update tools for a specific user.
   *
   * This replaces all existing non-default tools for the user with the provided list.
   * Default tools are always preserved and cannot be replaced.
   * Tools are persisted to the database and handlers are registered in the handler registry.
   *
   * @param userId The user identifier
   * @param tools List of tools to register for the user
   * @return Either error messages or success with tool count
   */
  def updateTools(userId: String, tools: List[DynamicTool]): Either[List[String], Int] = {
    require(userId.nonEmpty, "userId cannot be empty")

    logger.info(s"Updating tools for user $userId: ${tools.size} tools provided")

    // Validate all tools first
    val validationResults = tools.map(DynamicTool.validate)
    val errors = validationResults.collect { case Left(error) => error }

    if (errors.nonEmpty) {
      logger.warn(s"Tool validation failed for user $userId: ${errors.mkString(", ")}")
      Left(errors)
    } else {
      // Check for duplicate tool names
      val toolNames = tools.map(_.name)
      val duplicates = toolNames.groupBy(identity).filter(_._2.size > 1).keys.toList

      if (duplicates.nonEmpty) {
        logger.warn(s"Duplicate tool names for user $userId: ${duplicates.mkString(", ")}")
        Left(List(s"Duplicate tool names: ${duplicates.mkString(", ")}"))
      } else {
        // Check if any custom tools conflict with default tool names
        val defaultToolNames = defaultTools.map(_.name).toSet
        val conflicts = toolNames.filter(defaultToolNames.contains)

        if (conflicts.nonEmpty) {
          logger.warn(s"Tool name conflicts with defaults for user $userId: ${conflicts.mkString(", ")}")
          Left(List(s"Tool names conflict with default tools: ${conflicts.mkString(", ")}"))
        } else {
          // All validations passed, persist to database
          val nonDefaultTools = tools.filterNot(_.isDefault)

          Try {
            // Persist to database
            val persistFuture = toolsDAO.replaceToolsForUser(userId, nonDefaultTools)
            Await.result(persistFuture, dbTimeout)

            // Register handlers in handler registry
            nonDefaultTools.foreach { tool =>
              if (tool.handler != null) {
                handlerRegistry.put(tool.name, tool.handler)
              }
            }

            // Update cache
            userToolsCache.put(userId, nonDefaultTools)

            logger.info(s"Successfully updated ${nonDefaultTools.size} custom tools for user $userId: ${nonDefaultTools.map(_.name).mkString(", ")}")
            nonDefaultTools.size
          } match {
            case Success(count) => Right(count)
            case Failure(ex) =>
              logger.error(s"Failed to persist tools for user $userId", ex)
              Left(List(s"Database error: ${ex.getMessage}"))
          }
        }
      }
    }
  }

  /**
   * Get all tools for a specific user (default + custom).
   *
   * This method uses a cache-aside pattern:
   * 1. Check cache first
   * 2. If not in cache, load from database
   * 3. Associate handlers from registry
   * 4. Update cache
   *
   * @param userId The user identifier
   * @return List of all tools available to the user
   */
  def getToolsForUser(userId: String): List[DynamicTool] = {
    // Check cache first
    val customTools = Option(userToolsCache.get(userId)) match {
      case Some(cached) =>
        logger.debug(s"Cache hit for user $userId tools")
        cached

      case None =>
        // Cache miss - load from database
        logger.debug(s"Cache miss for user $userId, loading from database")
        Try {
          val loadFuture = toolsDAO.getToolsForUser(userId)
          val toolsFromDb = Await.result(loadFuture, dbTimeout)

          // Associate handlers from registry
          val toolsWithHandlers = toolsFromDb.map { tool =>
            val handler = Option(handlerRegistry.get(tool.name)).orNull
            tool.copy(handler = handler)
          }

          // Update cache
          userToolsCache.put(userId, toolsWithHandlers)
          toolsWithHandlers
        } match {
          case Success(tools) => tools
          case Failure(ex) =>
            logger.error(s"Failed to load tools from database for user $userId", ex)
            List.empty // Return empty on failure, default tools will still be available
        }
    }

    val allTools = defaultTools ++ customTools

    logger.debug(s"Retrieved ${allTools.size} tools for user $userId (${defaultTools.size} default + ${customTools.size} custom)")
    allTools
  }

  /**
   * Get only custom (non-default) tools for a user.
   *
   * @param userId The user identifier
   * @return List of custom tools for the user
   */
  def getCustomToolsForUser(userId: String): List[DynamicTool] = {
    // This leverages getToolsForUser which handles cache/database logic
    getToolsForUser(userId).filterNot(_.isDefault)
  }

  /**
   * Get default tools available to all users.
   *
   * @return List of default tools
   */
  def getDefaultTools: List[DynamicTool] = defaultTools

  /**
   * Remove all custom tools for a user (revert to defaults only).
   *
   * @param userId The user identifier
   * @return Number of tools removed
   */
  def clearUserTools(userId: String): Int = {
    Try {
      val deleteFuture = toolsDAO.deleteAllToolsForUser(userId)
      val removed = Await.result(deleteFuture, dbTimeout)

      // Clear cache
      userToolsCache.remove(userId)

      logger.info(s"Cleared $removed custom tools for user $userId from database")
      removed
    } match {
      case Success(count) => count
      case Failure(ex) =>
        logger.error(s"Failed to clear tools for user $userId", ex)
        0
    }
  }

  /**
   * Get tool names for a user.
   *
   * @param userId The user identifier
   * @return List of tool names available to the user
   */
  def getToolNamesForUser(userId: String): List[String] = {
    getToolsForUser(userId).map(_.name)
  }

  /**
   * Check if a user has a specific tool.
   *
   * @param userId The user identifier
   * @param toolName The tool name to check
   * @return True if the user has access to the tool
   */
  def hasToolForUser(userId: String, toolName: String): Boolean = {
    getToolsForUser(userId).exists(_.name == toolName)
  }

  /**
   * Get statistics about registered tools.
   *
   * @return Map with statistics
   */
  def getStats: Map[String, Any] = {
    val usersWithTools = Try {
      val usersFuture = toolsDAO.getUsersWithTools
      Await.result(usersFuture, dbTimeout)
    }.getOrElse(List.empty)

    Map(
      "defaultToolCount" -> defaultTools.size,
      "totalUsers" -> usersWithTools.size,
      "defaultToolNames" -> defaultTools.map(_.name),
      "usersWithCustomTools" -> usersWithTools,
      "cachedUsers" -> userToolsCache.size()
    )
  }

  /**
   * Register a handler for a tool name.
   * This is useful when loading tools from database and need to associate handlers.
   *
   * @param toolName The tool name
   * @param handler The handler function
   */
  def registerHandler(toolName: String, handler: ToolHandler): Unit = {
    handlerRegistry.put(toolName, handler)
    logger.debug(s"Registered handler for tool: $toolName")
  }

  /**
   * Close the database connection pool.
   * Call this when shutting down the application.
   */
  def close(): Unit = {
    logger.info("Closing ToolsService database connection")
    toolsDAO.close()
  }

  /**
   * Convert user's tools to McpTool format for MCP SDK.
   *
   * @param userId The user identifier
   * @return List of McpTool instances
   */
  def getMcpToolsForUser(userId: String): List[McpTool] = {
    val dynamicTools = getToolsForUser(userId)
    val convertedTools = dynamicTools.map(DynamicTool.toMcpTool)

    // Log any conversion failures
    convertedTools.collect {
      case Failure(ex) =>
        logger.error(s"Failed to convert dynamic tool to MCP tool: ${ex.getMessage}")
    }

    // Return only successful conversions
    convertedTools.collect {
      case Success(mcpTool) => mcpTool
    }
  }

  /**
   * Create an McpService instance for a specific user.
   *
   * This combines default tools with user-specific tools.
   *
   * @param userId The user identifier
   * @return McpService instance with user's tools
   */
  def createServiceForUser(userId: String): McpService = {
    new McpService {
      override val tools: Seq[McpTool] = getMcpToolsForUser(userId)
    }
  }
}

object ToolsService {
  /**
   * Create a new ToolsService instance
   *
   * @param config Application configuration
   * @param ec Execution context for async database operations
   * @return ToolsService instance
   */
  def apply(config: Config)(implicit ec: ExecutionContext): ToolsService = new ToolsService(config)
}
