package app.dragon.turnstile.service

import app.dragon.turnstile.db.{DbInterface, McpServerRow}
import app.dragon.turnstile.utils.ServiceValidationUtil.*
import dragon.turnstile.api.v1.*
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.JdbcBackend.Database

import java.sql.Timestamp
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Companion object for TurnstileServiceImpl.
 */
object TurnstileServiceImpl {
  // Placeholder for future companion object utilities
  def apply()(implicit db: Database): TurnstileServiceImpl =
    new TurnstileServiceImpl()
}

/**
 * Implementation of the TurnstileService gRPC service.
 *
 * This service provides MCP server registration functionality through gRPC.
 */
class TurnstileServiceImpl()(implicit db: Database) extends TurnstileService {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileServiceImpl])
  
  implicit private val ec: ExecutionContext = ExecutionContext.global
  
  /**
   * Create a new MCP server registration.
   *
   * @param request CreateMcpServerRequest containing name and url
   * @return Future[McpServer] containing the created MCP server info
   */
  override def createMcpServer(
    request: CreateMcpServerRequest
  ): Future[McpServer] = {
    logger.info(s"Received CreateMcpServer request for name: ${request.name}, url: ${request.url}")

    val now = Timestamp(System.currentTimeMillis())

    // Validate request using generic utility
    for {
      _ <- validateNotEmpty(request.name, "name")
      _ <- validateNotEmpty(request.url, "url")
      insertResult <- DbInterface.insertMcpServer(McpServerRow(
        uuid = UUID.nameUUIDFromBytes(s"${request.name}:${request.url}".getBytes("UTF-8")).toString,
        tenant = "default", // TODO: Replace with actual tenant id from auth context
        userId = "changeThisToUserId", // TODO: Replace with actual user id from auth context
        name = request.name,
        url = request.url,
        clientId = None,
        clientSecret = None,
        refreshToken = None,
        createdAt = now,
        updatedAt = now
      ))
    } yield {
      insertResult match {
        case Left(dbError) =>
          logger.error(s"Failed to insert MCP server into DB: ${dbError.message}")
          throw new RuntimeException(s"Database error: ${dbError.message}")
        case Right(row) =>
          logger.info(s"Successfully created MCP server with UUID: ${row.uuid}")
          McpServer(
            uuid = row.uuid,
            name = row.name,
            url = row.url
          )
      }
    }
  }

  /**
   * List all MCP servers for a user.
   *
   * @param request ListMcpServersRequest containing the userId
   * @return Future[McpServerList] containing list of registered MCP servers
   */
  override def listMcpServers(request: ListMcpServersRequest): Future[McpServerList] = {
    logger.info(s"Received ListMcpServers request for userId: ${request.userId}")

    // Validate request and list servers
    for {
      _ <- validateNotEmpty(request.userId, "userId")
      // TODO: Replace "default" with actual tenant from auth context
      listResult <- DbInterface.listMcpServers(tenant = "default", userId = request.userId)
    } yield {
      listResult match {
        case Left(dbError) =>
          logger.error(s"Failed to list MCP servers from DB: ${dbError.message}")
          throw new RuntimeException(s"Database error: ${dbError.message}")
        case Right(rows) =>
          val servers = rows.map(row => McpServer(
            uuid = row.uuid,
            name = row.name,
            url = row.url
          ))
          logger.info(s"Returning ${servers.size} MCP servers for userId: ${request.userId}")
          McpServerList(mcpServer = servers)
      }
    }
  }
}
