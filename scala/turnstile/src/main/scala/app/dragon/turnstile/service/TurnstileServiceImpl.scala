package app.dragon.turnstile.service

import dragon.turnstile.api.v1.{CreateMcpServerRequest, ListMcpServersRequest, McpServer, McpServerList, TurnstileService}
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.{Logger, LoggerFactory}
import app.dragon.turnstile.db.{McpServerRow, TableInserter}
import slick.jdbc.JdbcBackend.Database
import java.sql.Timestamp

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import app.dragon.turnstile.utils.ServiceValidationUtil._

/**
 * Companion object for TurnstileServiceImpl.
 */
object TurnstileServiceImpl {
  // Placeholder for future companion object utilities
}

/**
 * Implementation of the TurnstileService gRPC service.
 *
 * This service provides MCP server registration functionality through gRPC.
 */
class TurnstileServiceImpl(system: ActorSystem[_])(implicit db: Database) extends TurnstileService {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileServiceImpl])
  private implicit val sys: ActorSystem[_] = system
  private implicit val ec: ExecutionContext = system.executionContext

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
      insertedRow <- TableInserter.insertMcpServer(McpServerRow(
          id = 0L,
          uuid = UUID.nameUUIDFromBytes(s"${request.name}:${request.url}".getBytes("UTF-8")).toString,
          tenant = "default", // TODO: Replace with actual tenant id from auth context
          userId = "default", // TODO: Replace with actual user id from auth context
          name = request.name,
          url = request.url,
          clientId = None,
          clientSecret = None,
          refreshToken = None,
          createdAt = now,
          updatedAt = now
        ))
    } yield {
      logger.info(s"Successfully created MCP server ${insertedRow.name} with uuid ${insertedRow.uuid}")
      McpServer(
        uuid = insertedRow.uuid,
        name = insertedRow.name,
        url = insertedRow.url
      )
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
      rows <- TableInserter.listMcpServers(tenant = "default", userId = request.userId)
    } yield {
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
