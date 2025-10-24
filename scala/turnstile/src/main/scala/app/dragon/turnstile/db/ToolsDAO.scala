package app.dragon.turnstile.db

import app.dragon.turnstile.service.DynamicTool
import app.dragon.turnstile.db.TurnstilePostgresProfile.api.{*, given}
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsValue, Json}

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Data Access Object for tools persistence.
 *
 * Provides async database operations for user tools:
 * - Create/update tools for a user
 * - Retrieve tools by user ID
 * - Delete tools for a user
 * - Check tool existence
 *
 * Thread-safe: All operations return Futures and use Slick's async API.
 */
class ToolsDAO(config: Config)(implicit ec: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[ToolsDAO])

  // Database configuration from application.conf
  private val db = Database.forConfig("turnstile.database.db", config)

  /**
   * Get all tools for a specific user
   */
  def getToolsForUser(userId: String): Future[List[DynamicTool]] = {
    val query = Tables.userTools.filter(_.userId === userId).result

    db.run(query).map { rows =>
      rows.map(rowToTool).toList
    }.recoverWith { case ex =>
      logger.error(s"Failed to get tools for user $userId", ex)
      Future.failed(ex)
    }
  }

  /**
   * Get a specific tool for a user by name
   */
  def getToolByName(userId: String, toolName: String): Future[Option[DynamicTool]] = {
    val query = Tables.userTools
      .filter(row => row.userId === userId && row.toolName === toolName)
      .result
      .headOption

    db.run(query).map(_.map(rowToTool)).recoverWith { case ex =>
      logger.error(s"Failed to get tool $toolName for user $userId", ex)
      Future.failed(ex)
    }
  }

  /**
   * Get a specific tool by id and user
   */
  def getToolById(userId: String, toolId: Long): Future[Option[DynamicTool]] = {
    val query = Tables.userTools
      .filter(row => row.id === toolId && row.userId === userId)
      .result
      .headOption

    db.run(query).map(_.map(rowToTool)).recoverWith { case ex =>
      logger.error(s"Failed to get tool with id $toolId for user $userId", ex)
      Future.failed(ex)
    }
  }

  /**
   * Check if a tool exists for a user
   */
  def toolExists(userId: String, toolName: String): Future[Boolean] = {
    val query = Tables.userTools
      .filter(row => row.userId === userId && row.toolName === toolName)
      .exists
      .result

    db.run(query).recoverWith { case ex =>
      logger.error(s"Failed to check if tool $toolName exists for user $userId", ex)
      Future.failed(ex)
    }
  }

  /**
   * Replace all tools for a user (transactional)
   * This deletes all existing tools and inserts new ones atomically.
   */
  def replaceToolsForUser(userId: String, tools: List[DynamicTool]): Future[Int] = {
    val timestamp = new Timestamp(System.currentTimeMillis())
    val rows = tools.map(tool => toolToRow(userId, tool, timestamp))

    val action = (for {
      // Delete existing tools for this user
      _ <- Tables.userTools.filter(_.userId === userId).delete
      // Insert new tools
      inserted <- Tables.userTools ++= rows
    } yield inserted.getOrElse(0)).transactionally

    db.run(action).andThen {
      case Success(count) =>
        logger.info(s"Replaced tools for user $userId: $count tools inserted")
      case Failure(ex) =>
        logger.error(s"Failed to replace tools for user $userId", ex)
    }
  }

  /**
   * Create a new tool for a user and return it with the generated id
   */
  def createTool(userId: String, tool: DynamicTool): Future[DynamicTool] = {
    val timestamp = new Timestamp(System.currentTimeMillis())
    val row = toolToRow(userId, tool, timestamp)

    val action = (Tables.userTools returning Tables.userTools.map(_.id)
      into ((row, id) => row.copy(id = id))) += row

    db.run(action).map { insertedRow =>
      rowToTool(insertedRow)
    }.andThen {
      case Success(created) =>
        logger.info(s"Created tool ${tool.name} with id ${created.id} for user $userId")
      case Failure(ex) =>
        logger.error(s"Failed to create tool ${tool.name} for user $userId", ex)
    }
  }

  /**
   * Insert or update a single tool for a user
   */
  def upsertTool(userId: String, tool: DynamicTool): Future[Int] = {
    val timestamp = new Timestamp(System.currentTimeMillis())
    val row = toolToRow(userId, tool, timestamp)

    val action = Tables.userTools.insertOrUpdate(row)

    db.run(action).andThen {
      case Success(count) =>
        logger.debug(s"Upserted tool ${tool.name} for user $userId")
      case Failure(ex) =>
        logger.error(s"Failed to upsert tool ${tool.name} for user $userId", ex)
    }
  }

  /**
   * Delete a specific tool for a user by name
   */
  def deleteTool(userId: String, toolName: String): Future[Int] = {
    val action = Tables.userTools
      .filter(row => row.userId === userId && row.toolName === toolName)
      .delete

    db.run(action).andThen {
      case Success(count) =>
        logger.debug(s"Deleted tool $toolName for user $userId: $count rows affected")
      case Failure(ex) =>
        logger.error(s"Failed to delete tool $toolName for user $userId", ex)
    }
  }

  /**
   * Delete a specific tool by id and user
   */
  def deleteToolById(userId: String, toolId: Long): Future[Int] = {
    val action = Tables.userTools
      .filter(row => row.id === toolId && row.userId === userId)
      .delete

    db.run(action).andThen {
      case Success(count) =>
        logger.debug(s"Deleted tool with id $toolId for user $userId: $count rows affected")
      case Failure(ex) =>
        logger.error(s"Failed to delete tool with id $toolId for user $userId", ex)
    }
  }

  /**
   * Delete all tools for a user
   */
  def deleteAllToolsForUser(userId: String): Future[Int] = {
    val action = Tables.userTools.filter(_.userId === userId).delete

    db.run(action).andThen {
      case Success(count) =>
        logger.info(s"Deleted all tools for user $userId: $count rows affected")
      case Failure(ex) =>
        logger.error(s"Failed to delete all tools for user $userId", ex)
    }
  }

  /**
   * Get count of tools for a user
   */
  def getToolCount(userId: String): Future[Int] = {
    val query = Tables.userTools.filter(_.userId === userId).length.result

    db.run(query).recoverWith { case ex =>
      logger.error(s"Failed to get tool count for user $userId", ex)
      Future.failed(ex)
    }
  }

  /**
   * Get all user IDs that have custom tools
   */
  def getUsersWithTools: Future[List[String]] = {
    val query = Tables.userTools.map(_.userId).distinct.result

    db.run(query).map(_.toList).recoverWith { case ex =>
      logger.error("Failed to get users with tools", ex)
      Future.failed(ex)
    }
  }

  /**
   * Close the database connection pool
   */
  def close(): Unit = {
    logger.info("Closing database connection pool")
    db.close()
  }

  // Helper: Convert database row to DynamicTool
  private def rowToTool(row: UserToolRow): DynamicTool = {
    DynamicTool(
      id = Some(row.id),
      name = row.toolName,
      description = row.description,
      schemaJson = Json.stringify(row.schemaJson), // Convert JsValue to String
      handler = null, // Handler is not persisted, will be set by ToolsService
      isDefault = false
    )
  }

  // Helper: Convert DynamicTool to database row
  private def toolToRow(userId: String, tool: DynamicTool, timestamp: Timestamp): UserToolRow = {
    // Parse JSON string to JsValue
    val schemaJsonValue = Try(Json.parse(tool.schemaJson)).getOrElse {
      logger.warn(s"Failed to parse JSON schema for tool ${tool.name}, using empty object")
      Json.obj()
    }

    UserToolRow(
      id = tool.id.getOrElse(0L), // 0L for new tools (auto-generated)
      userId = userId,
      toolName = tool.name,
      description = tool.description,
      schemaJson = schemaJsonValue,
      createdAt = timestamp,
      updatedAt = timestamp
    )
  }
}

object ToolsDAO {
  def apply(config: Config)(implicit ec: ExecutionContext): ToolsDAO = {
    new ToolsDAO(config)
  }
}
