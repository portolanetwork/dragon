package app.dragon.turnstile.service

import app.dragon.turnstile.db.*
import app.dragon.turnstile.utils.ServiceValidationUtil.*
import com.google.protobuf.empty.Empty
import dragon.turnstile.api.v1.*
import io.grpc.Status
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.grpc.scaladsl.MetadataBuilder
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

  // TODO: Integrate auth. Currently userId and tenant are hardcoded or passed in requests.

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
      _ <- validateHasNoSpaces(request.name, "name")
      _ <- validateNotEmpty(request.url, "url")
      insertResult <- DbInterface.insertMcpServer(McpServerRow(
        tenant = "default", // TODO: Replace with actual tenant id from auth context
        userId = "changeThisToUserId", // TODO: Replace with actual user id from auth context
        name = request.name,
        url = request.url,
        createdAt = now,
        updatedAt = now
      ))
    } yield {
      insertResult match {
        case Left(DbAlreadyExists) =>
          logger.warn(s"MCP server already exists: ${DbAlreadyExists.message}")
          throw new GrpcServiceException(Status.ALREADY_EXISTS, MetadataBuilder().addText("ALREADY_EXISTS", "Resource already exists").build())
        case Left(dbError) =>
          logger.error(s"Failed to insert MCP server into DB: ${dbError.message}")
          throw new GrpcServiceException(Status.UNKNOWN, MetadataBuilder().addText("UNHANDLED_ERROR", dbError.message).build())
        case Right(row) =>
          logger.info(s"Successfully created MCP server with UUID: ${row.uuid}")

          // TODO: ToolServer must pick up new server registration at runtime and start using it

          McpServer(uuid = row.uuid.toString, name = row.name, url = row.url)
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
        case Left(DbAlreadyExists) =>
          logger.warn(s"DB reported already-exists when listing for userId: ${request.userId}")
          throw new GrpcServiceException(Status.ALREADY_EXISTS, MetadataBuilder().addText("ALREADY_EXISTS", "Resource already exists").build())
        case Left(DbNotFound) =>
          logger.warn(s"No MCP servers found for userId: ${request.userId}")
          throw new GrpcServiceException(Status.NOT_FOUND, MetadataBuilder().addText("NOT_FOUND", "No resources").build())
        case Left(dbError: DbFailure) =>
          logger.error(s"Failed to list MCP servers from DB: ${dbError.message}")
          throw new GrpcServiceException(Status.UNKNOWN, MetadataBuilder().addText("UNHANDLED_ERROR", dbError.message).build())
        case Right(rows) =>
          val servers = rows.map(row => McpServer(
            uuid = row.uuid.toString,
            name = row.name,
            url = row.url
          ))
          logger.info(s"Returning ${servers.size} MCP servers for userId: ${request.userId}")
          McpServerList(mcpServer = servers)
      }
    }
  }

  /**
   * Delete an MCP server by UUID.
   *
   * @param request DeleteMcpServerRequest containing the uuid
   * @return Future[Empty] on successful deletion
   */
  override def deleteMcpServer(request: DeleteMcpServerRequest): Future[Empty] = {
    logger.info(s"Received DeleteMcpServer request for uuid: ${request.uuid}")

    // Validate request and delete server
    for {
      _ <- validateNotEmpty(request.uuid, "uuid")
      uuid <- Future.fromTry(scala.util.Try(UUID.fromString(request.uuid)))
        .recoverWith(_ => Future.failed(new GrpcServiceException(Status.INVALID_ARGUMENT,
          MetadataBuilder().addText("INVALID_UUID", s"Invalid UUID format: ${request.uuid}").build())))
      deleteResult <- DbInterface.deleteMcpServerByUuid(uuid)
    } yield {
      deleteResult match {
        case Left(DbAlreadyExists) =>
          logger.warn(s"DB reported already-exists when deleting uuid: ${request.uuid}")
          throw new GrpcServiceException(Status.ALREADY_EXISTS, MetadataBuilder().addText("ALREADY_EXISTS", "Resource already exists").build())
        case Left(DbNotFound) =>
          logger.warn(s"No MCP server found to delete with UUID: ${request.uuid}")
          throw new GrpcServiceException(Status.NOT_FOUND, MetadataBuilder().addText("NOT_FOUND", s"No resource with uuid ${request.uuid}").build())
        case Left(dbError: DbFailure) =>
          logger.error(s"Failed to delete MCP server from DB: ${dbError.message}")
          throw new GrpcServiceException(Status.UNKNOWN, MetadataBuilder().addText("UNHANDLED_ERROR", dbError.message).build())
        case Right(rowsDeleted) =>
          if (rowsDeleted == 0) {
            logger.warn(s"No MCP server found with UUID: ${request.uuid}")
            throw new GrpcServiceException(Status.NOT_FOUND, MetadataBuilder().addText("NOT_FOUND", s"MCP server not found: ${request.uuid}").build())
          } else {
            logger.info(s"Successfully deleted MCP server with UUID: ${request.uuid}")
            Empty()
          }
      }
    }
  }
}
