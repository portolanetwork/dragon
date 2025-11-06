/*
 * Copyright 2025 Sami Malik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: Sami Malik (sami.malik [at] portolanetwork.io)
 */

package app.dragon.turnstile.service

import app.dragon.turnstile.auth.AuthService
import app.dragon.turnstile.db.*
import app.dragon.turnstile.utils.ServiceValidationUtil.*
import com.google.protobuf.empty.Empty
import dragon.turnstile.api.v1.*
import io.grpc.Status
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.grpc.scaladsl.{Metadata, MetadataBuilder}
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
class TurnstileServiceImpl()(
  implicit db: Database
) extends TurnstileServicePowerApi {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileServiceImpl])
  
  implicit private val ec: ExecutionContext = ExecutionContext.global

  /**
   * Create a new MCP server registration.
   *
   * @param request CreateMcpServerRequest containing name and url
   * @return Future[McpServer] containing the created MCP server info
   */
  override def createMcpServer(
    request: CreateMcpServerRequest,
    metadata: Metadata,
  ): Future[McpServer] = {
    val now = Timestamp(System.currentTimeMillis())

    // Validate request using generic utility
    for {
      authContext <- AuthService.authenticate(metadata)
      //_ = logger.info(s"Received CreateMcpServer request for name: ${request.name}, url: ${request.url} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(request.name, "name")
      _ <- validateHasNoSpaces(request.name, "name")
      _ <- validateNotEmpty(request.url, "url")
      insertResult <- DbInterface.insertMcpServer(McpServerRow(
        tenant = authContext.tenant,
        userId = authContext.userId,
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
  override def listMcpServers(
    request: ListMcpServersRequest,
    metadata: Metadata,
  ): Future[McpServerList] = {
    // Validate request and list servers
    for {
      authContext <- AuthService.authenticate(metadata)
      _ = logger.info(s"Received ListMcpServers request for userId: ${request.userId} from authenticated userId: ${authContext.userId}")
      _ <- validateNotEmpty(request.userId, "userId")
      listResult <- DbInterface.listMcpServers(tenant = authContext.tenant, userId = request.userId)
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
  override def deleteMcpServer(
    request: DeleteMcpServerRequest,
    metadata: Metadata,
  ): Future[Empty] = {
    // Validate request and delete server
    for {
      authContext <- AuthService.authenticate(metadata)
      _ = logger.info(s"Received DeleteMcpServer request for uuid: ${request.uuid} from userId: ${authContext.userId}")
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
