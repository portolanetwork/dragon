package app.dragon.turnstile.service

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.db.ToolsDAO
import app.dragon.turnstile.service.tools.{EchoTool, SystemInfoTool}
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
 * This service provides a simplified API for CRUD operations on user tools:
 * - createTool: Create a new tool for a user
 * - getTool: Get a specific tool by id
 * - getTools: Get all tools for a user (default + custom)
 * - deleteTool: Delete a tool by id
 *
 * Key features:
 * - Per-user tool registration
 * - Database persistence with PostgreSQL
 * - Preserves default (built-in) tools
 * - Thread-safe concurrent access
 * - Tool validation
 * - JSON schema support for tool definitions
 * - ID-based tool identification
 *
 * Note: Handlers are not persisted to the database. When loading tools from the database,
 * handlers must be registered using registerHandler().
 *
 * Example usage:
 * {{{
 * val toolsService = ToolsService.instance
 *
 * // Define a custom tool with JSON schema
 * val customTool = DynamicTool(
 *   name = "custom_calculator",
 *   description = "Custom calculator for user",
 *   schemaJson = """{"type": "object", "properties": {"x": {"type": "number"}}}""",
 *   handler = (ctx, req) => { /* implementation */ }
 * )
 *
 * // Create a tool for a user
 * toolsService.createTool("user123", customTool).map {
 *   case Right(created) => println(s"Created tool with id: ${created.id}")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 *
 * // Get all tools for a user
 * toolsService.getTools("user123").map {
 *   case Right(tools) => println(s"Found ${tools.size} tools")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 *
 * // Get a specific tool by id
 * toolsService.getTool("user123", toolId).map {
 *   case Right(tool) => println(s"Found tool: ${tool.name}")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 *
 * // Delete a tool by id
 * toolsService.deleteTool("user123", toolId).map {
 *   case Right(count) => println(s"Deleted $count tool(s)")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 * }}}
 */


object ToolsService {

  sealed trait ToolsServiceError {
    def message: String
  }
  final case class AlreadyExists(message: String) extends ToolsServiceError
  final case class ValidationError(message: String) extends ToolsServiceError
  final case class ConflictError(message: String) extends ToolsServiceError
  final case class DatabaseError(message: String) extends ToolsServiceError

  /**
   * Singleton instance of ToolsService.
   *
   * Initializes lazily on first access with:
   * - Config loaded directly from ApplicationConfig.rootConfig
   * - Global ExecutionContext for async database operations
   *
   * This ensures a single shared instance across the application with
   * consistent database connection pooling and caching.
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

  // Default tools (from configuration)
  private val defaultTools: List[DynamicTool] = Seq(
    EchoTool().tool,
    SystemInfoTool().tool
  ).map(tool => DynamicTool.fromMcpTool(tool, isDefault = true)).toList

  // Handler registry: Maps tool names to their handlers
  // Since handlers are not persisted, we need a registry to associate them
  private val handlerRegistry = new ConcurrentHashMap[String, ToolHandler]()

  // Database DAO
  private val toolsDAO = ToolsDAO(config)

  logger.info(s"ToolsService initialized with ${defaultTools.size} default tools: ${defaultTools.map(_.name).mkString(", ")}")
  logger.info("Database persistence enabled for custom tools")

  /**
   * Create a new tool for a user.
   *
   * Validates the tool and persists it to the database. Handlers are registered
   * in the handler registry for later use.
   *
   * @param userId The user identifier
   * @param tool The tool to create
   * @return Future[Either[ToolsServiceError, DynamicTool]] with created tool including id
   */
  def createTool(userId: String, tool: DynamicTool): Future[Either[ToolsService.ToolsServiceError, DynamicTool]] = {
    require(userId.nonEmpty, "userId cannot be empty")

    logger.info(s"Creating tool ${tool.name} for user $userId")

    for {
      _ <- DynamicTool.validate(tool) match {
        case Left(error) => Future.successful(Left(ToolsService.ValidationError(error)))
        case Right(_) => Future.successful(Right(()))
      }
      _ <- if (defaultTools.map(_.name).toSet.contains(tool.name))
        Future.successful(Left(ToolsService.ConflictError(s"Tool name conflicts with default tool: ${tool.name}")))
      else Future.successful(Right(()))
      result <- toolsDAO.createTool(userId, tool).map { createdTool =>
        if (tool.handler != null) handlerRegistry.put(tool.name, tool.handler)
        logger.info(s"Successfully created tool ${tool.name} with id ${createdTool.id} for user $userId")
        Right(createdTool)
      }.recover { case ex =>
        logger.error(s"Failed to create tool ${tool.name} for user $userId", ex)
        Left(ToolsService.DatabaseError(s"Database error: ${ex.getMessage}"))
      }
    } yield result

  }

  /**
   * Delete a tool by id for a user.
   *
   * @param userId The user identifier
   * @param toolId The tool id to delete
   * @return Future[Either[ToolsServiceError, Int]] with number of deleted tools
   */
  def deleteTool(userId: String, toolId: Long): Future[Either[ToolsService.ToolsServiceError, Int]] = {
    require(userId.nonEmpty, "userId cannot be empty")

    logger.info(s"Deleting tool with id $toolId for user $userId")

    toolsDAO.deleteToolById(userId, toolId).map { count =>
      if (count > 0) {
        logger.info(s"Successfully deleted tool with id $toolId for user $userId")
        Right(count)
      } else {
        logger.warn(s"Tool with id $toolId not found for user $userId")
        Left(ToolsService.ValidationError(s"Tool with id $toolId not found"))
      }
    }.recover { case ex =>
      logger.error(s"Failed to delete tool with id $toolId for user $userId", ex)
      Left(ToolsService.DatabaseError(s"Database error: ${ex.getMessage}"))
    }
  }

  /**
   * Get all tools for a user (default + custom).
   *
   * @param userId The user identifier
   * @return Future[Either[ToolsServiceError, List[DynamicTool]]] with all tools
   */
  def getTools(userId: String): Future[Either[ToolsService.ToolsServiceError, List[DynamicTool]]] = {
    require(userId.nonEmpty, "userId cannot be empty")

    logger.debug(s"Getting all tools for user $userId")

    toolsDAO.getToolsForUser(userId).map { customTools =>
      val toolsWithHandlers = customTools.map { tool =>
        val handler = Option(handlerRegistry.get(tool.name)).orNull
        tool.copy(handler = handler)
      }
      Right(defaultTools ++ toolsWithHandlers)
    }.recover { case ex =>
      logger.error(s"Failed to get tools for user $userId", ex)
      Left(ToolsService.DatabaseError(s"Database error: ${ex.getMessage}"))
    }
  }

  /**
   * Get a specific tool by id for a user.
   *
   * @param userId The user identifier
   * @param toolId The tool id to retrieve
   * @return Future[Either[ToolsServiceError, DynamicTool]] with the tool
   */
  def getTool(userId: String, toolId: Long): Future[Either[ToolsService.ToolsServiceError, DynamicTool]] = {
    require(userId.nonEmpty, "userId cannot be empty")

    logger.debug(s"Getting tool with id $toolId for user $userId")

    toolsDAO.getToolById(userId, toolId).map {
      case Some(tool) =>
        val handler = Option(handlerRegistry.get(tool.name)).orNull
        val toolWithHandler = tool.copy(handler = handler)
        Right(toolWithHandler)
      case None =>
        logger.warn(s"Tool with id $toolId not found for user $userId")
        Left(ToolsService.ValidationError(s"Tool with id $toolId not found"))
    }.recover { case ex =>
      logger.error(s"Failed to get tool with id $toolId for user $userId", ex)
      Left(ToolsService.DatabaseError(s"Database error: ${ex.getMessage}"))
    }
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
   * Get default tools available to all users.
   *
   * @return List of default tools
   */
  def getDefaultTools: List[DynamicTool] = defaultTools

  /**
   * Convert user's tools to McpTool format for MCP SDK.
   *
   * @param userId The user identifier
   * @return Future[List] of McpTool instances
   */
  def getMcpToolsForUser(userId: String): Future[List[McpTool]] = {
    getTools(userId).map {
      case Right(dynamicTools) =>
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
      case Left(err) =>
        logger.error(s"Failed to get tools for user $userId: ${err.message}")
        List.empty
    }
  }

  /**
   * Create an McpService instance for a specific user.
   *
   * This combines default tools with user-specific tools.
   *
   * @param userId The user identifier
   * @return Future[McpService] instance with user's tools
   */
  def createServiceForUser(userId: String): Future[McpService] = {
    getMcpToolsForUser(userId).map { mcpTools =>
      new McpService {
        override val tools: Seq[McpTool] = mcpTools
      }
    }
  }
}
