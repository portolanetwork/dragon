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

package app.dragon.turnstile.mgmt

import app.dragon.turnstile.auth.{ClientAuthService, ServerAuthService}
import app.dragon.turnstile.db.*
import app.dragon.turnstile.mcp_server.{McpServerActor, McpServerActorId}
import app.dragon.turnstile.mcp_tools.ToolsService
import app.dragon.turnstile.utils.ServiceValidationUtil.*
import app.dragon.turnstile.utils.{ActorLookup, ConversionUtils}
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
object MgmtServiceImpl {
  // Placeholder for future companion object utilities
  def apply(authEnabled: Boolean)(
    implicit db: Database,
    system: org.apache.pekko.actor.typed.ActorSystem[?],
    sharding: org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
  ): MgmtServiceImpl =
    new MgmtServiceImpl(authEnabled)
}

/**
 * Implementation of the TurnstileService gRPC service.
 *
 * This service provides MCP server registration functionality through gRPC.
 */
class MgmtServiceImpl(authEnabled: Boolean)(
  implicit db: Database,
  system: org.apache.pekko.actor.typed.ActorSystem[?],
  sharding: org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
) extends TurnstileServicePowerApi {
  private val logger: Logger = LoggerFactory.getLogger(classOf[MgmtServiceImpl])

  implicit private val ec: ExecutionContext = ExecutionContext.global
  
  /**
   * Create a new MCP server registration.
   *
   * @param in CreateMcpServerRequest containing name and url
   * @return Future[McpServer] containing the created MCP server info
   */
  override def addMcpServer(
    in: AddMcpServerRequest,
    metadata: Metadata,
  ): Future[McpServer] = {
    val now = Timestamp(System.currentTimeMillis())

    // Validate request using generic utility
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      //_ = logger.info(s"Received CreateMcpServer request for name: ${request.name}, url: ${request.url} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.name, "name")
      _ <- validateHasNoSpaces(in.name, "name")
      _ <- validateNotEmpty(in.url, "url")
      insertResult <- DbInterface.insertMcpServer(McpServerRow(
        tenant = authContext.tenant,
        userId = authContext.userId,
        name = in.name,
        url = in.url,
        authType = ConversionUtils.authTypeToString(in.authType),
        staticToken = if (in.staticToken.isEmpty) None else Some(in.staticToken),
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

          // Note: Tool servers will need to be notified of new server registrations at runtime

          ConversionUtils.rowToMcpServer(row)
      }
    }
  }

  /**
   * List all MCP servers for a user.
   *
   * @param in ListMcpServersRequest containing the userId
   * @return Future[McpServerList] containing list of registered MCP servers
   */
  override def listMcpServers(
    in: ListMcpServersRequest,
    metadata: Metadata,
  ): Future[McpServerList] = {
    // Validate request and list servers
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received ListMcpServers request for userId: ${in.userId} from authenticated userId: ${authContext.userId}")
      _ <- validateEquals(in.userId, authContext.userId, "User ID mismatch")
      _ <- validateNotEmpty(in.userId, "userId")
      listResult <- DbInterface.listMcpServers(tenant = authContext.tenant, userId = authContext.userId)
    } yield {
      listResult match {
        case Left(DbAlreadyExists) =>
          logger.warn(s"DB reported already-exists when listing for userId: ${in.userId}")
          throw new GrpcServiceException(Status.ALREADY_EXISTS, MetadataBuilder().addText("ALREADY_EXISTS", "Resource already exists").build())
        case Left(DbNotFound) =>
          logger.warn(s"No MCP servers found for userId: ${in.userId}")
          throw new GrpcServiceException(Status.NOT_FOUND, MetadataBuilder().addText("NOT_FOUND", "No resources").build())
        case Left(dbError: DbFailure) =>
          logger.error(s"Failed to list MCP servers from DB: ${dbError.message}")
          throw new GrpcServiceException(Status.UNKNOWN, MetadataBuilder().addText("UNHANDLED_ERROR", dbError.message).build())
        case Right(rows) =>
          val servers = rows.map(ConversionUtils.rowToMcpServer)
          logger.info(s"Returning ${servers.size} MCP servers for userId: ${in.userId}")
          McpServerList(mcpServer = servers)
      }
    }
  }

  /**
   * Delete an MCP server by UUID.
   *
   * @param in DeleteMcpServerRequest containing the uuid
   * @return Future[Empty] on successful deletion
   */
  override def removeMcpServer(
    in: RemoveMcpServerRequest,
    metadata: Metadata,
  ): Future[Empty] = {
    // Validate request and delete server
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received DeleteMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      uuid <- Future.fromTry(scala.util.Try(UUID.fromString(in.uuid)))
        .recoverWith(_ => Future.failed(new GrpcServiceException(Status.INVALID_ARGUMENT,
          MetadataBuilder().addText("INVALID_UUID", s"Invalid UUID format: ${in.uuid}").build())))
      deleteResult <- DbInterface.deleteMcpServerByUuid(authContext.tenant, authContext.userId, uuid)
    } yield {
      deleteResult match {
        case Left(DbAlreadyExists) =>
          logger.warn(s"DB reported already-exists when deleting uuid: ${in.uuid}")
          throw new GrpcServiceException(Status.ALREADY_EXISTS, MetadataBuilder().addText("ALREADY_EXISTS", "Resource already exists").build())
        case Left(DbNotFound) =>
          logger.warn(s"No MCP server found to delete with UUID: ${in.uuid}")
          throw new GrpcServiceException(Status.NOT_FOUND, MetadataBuilder().addText("NOT_FOUND", s"No resource with uuid ${in.uuid}").build())
        case Left(dbError: DbFailure) =>
          logger.error(s"Failed to delete MCP server from DB: ${dbError.message}")
          throw new GrpcServiceException(Status.UNKNOWN, MetadataBuilder().addText("UNHANDLED_ERROR", dbError.message).build())
        case Right(rowsDeleted) =>
          if (rowsDeleted == 0) {
            logger.warn(s"No MCP server found with UUID: ${in.uuid}")
            throw new GrpcServiceException(Status.NOT_FOUND, MetadataBuilder().addText("NOT_FOUND", s"MCP server not found: ${in.uuid}").build())
          } else {
            logger.info(s"Successfully deleted MCP server with UUID: ${in.uuid}")
            Empty()
          }
      }
    }
  }

  /**
   * Initiate OAuth login flow for an MCP server.
   *
   * @param in       LoginMcpServerRequest containing the uuid
   * @param metadata Request metadata containing authentication info
   * @return Future[McpServerLoginUrl] containing the login URL to redirect to
   */
  override def loginMcpServer(
    in: LoginMcpServerRequest,
    metadata: Metadata
  ): Future[McpServerLoginUrl] = {
    // Validate request and initiate auth flow
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received LoginToMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      loginUrlResult <- ClientAuthService.initiateAuthCodeFlow(authContext.tenant, authContext.userId, in.uuid)
    } yield {
      loginUrlResult match {
        case Right(loginUrl) =>
          logger.info(s"Successfully initiated OAuth flow for MCP server UUID: ${in.uuid}, login URL: $loginUrl")
          McpServerLoginUrl(loginUrl = loginUrl)
        case Left(ClientAuthService.ServerNotFound(msg)) =>
          logger.warn(s"MCP server not found: $msg")
          throw new GrpcServiceException(Status.NOT_FOUND,
            MetadataBuilder().addText("NOT_FOUND", msg).build())
        case Left(ClientAuthService.DatabaseError(msg)) =>
          logger.error(s"Database error during OAuth flow initiation: $msg")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("DATABASE_ERROR", msg).build())
        case Left(ClientAuthService.MissingConfiguration(msg)) =>
          logger.error(s"Missing configuration for OAuth flow: $msg")
          throw new GrpcServiceException(Status.FAILED_PRECONDITION,
            MetadataBuilder().addText("MISSING_CONFIGURATION", msg).build())
        case Left(authError) =>
          logger.error(s"Failed to initiate OAuth flow: ${authError.message}")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("AUTH_FLOW_FAILED", authError.message).build())
      }
    }
  }

  override def getLoginStatusForMcpServer(
    in: GetLoginStatusForMcpServerRequest,
    metadata: Metadata
  ): Future[McpServerLoginStatus] = {
    // Validate request and get login status
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received GetLoginStatusForMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      loginStatusResult <- ClientAuthService.getMcpServerLoginStatus(authContext.tenant, authContext.userId, in.uuid)(db, ec, system)
    } yield {
      loginStatusResult match {
        case Right(statusInfo: ClientAuthService.LoginStatusInfo) =>
          logger.info(s"Retrieved login status for MCP server UUID: ${in.uuid}")
          
          McpServerLoginStatus(
            uuid = statusInfo.uuid,
            status = ConversionUtils.determineLoginStatus(statusInfo.loginStatusEnum),
            authType = ConversionUtils.stringToAuthType(statusInfo.authType)
          )
        case Left(ClientAuthService.ServerNotFound(msg)) =>
          logger.warn(s"MCP server not found: $msg")
          throw new GrpcServiceException(Status.NOT_FOUND,
            MetadataBuilder().addText("NOT_FOUND", msg).build())
        case Left(ClientAuthService.DatabaseError(msg)) =>
          logger.error(s"Database error while retrieving login status: $msg")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("DATABASE_ERROR", msg).build())
        case Left(authError) =>
          logger.error(s"Failed to retrieve login status: ${authError.message}")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("AUTH_STATUS_FAILED", authError.message).build())
      }
    }
  }

  /**
   * Logout from an MCP server by clearing cached tokens and removing refresh token.
   *
   * @param in       LogoutMcpServerRequest containing the uuid
   * @param metadata Request metadata containing authentication info
   * @return Future[Empty] on successful logout
   */
  override def logoutMcpServer(
    in: LogoutMcpServerRequest,
    metadata: Metadata
  ): Future[Empty] = {
    // Validate request and logout
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received LogoutFromMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      logoutResult <- ClientAuthService.logoutFromMcpServer(in.uuid)(db, ec)
    } yield {
      logoutResult match {
        case Right(_) =>
          logger.info(s"Successfully logged out from MCP server UUID: ${in.uuid}")
          Empty()
        case Left(ClientAuthService.DatabaseError(msg)) =>
          logger.error(s"Database error during logout: $msg")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("DATABASE_ERROR", msg).build())
        case Left(authError) =>
          logger.error(s"Failed to logout: ${authError.message}")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("LOGOUT_FAILED", authError.message).build())
      }
    }
  }

  /**
   * Load tools from a downstream MCP server and add them to the user's MCP server actor.
   *
   * @param in       LoadToolsForMcpServerRequest containing the uuid of the downstream MCP server
   * @param metadata Request metadata containing authentication info
   * @return Future[Empty] on successful tool loading
   */
  override def loadToolsForMcpServer(
    in: LoadToolsForMcpServerRequest,
    metadata: Metadata
  ): Future[Empty] = {
    // Validate request and load tools
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received LoadToolsForMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      // Fetch the tool specifications for the given MCP server UUID
      toolsResult <- ToolsService.getInstance(authContext.userId).getDownstreamToolsSpec(in.uuid)
    } yield {
      toolsResult match {
        case Right(toolSpecs) =>
          logger.info(s"Successfully fetched ${toolSpecs.size} tools for MCP server UUID: ${in.uuid}")

          // Send AddTools message to the actor (fire and forget)
          ActorLookup.getMcpServerActor(McpServerActorId(authContext.userId)) ! McpServerActor.AddTools(toolSpecs)

          logger.info(s"Sent ${toolSpecs.size} tools to MCP server actor for user ${authContext.userId}")
          Empty()

        case Left(error) =>
          logger.error(s"Failed to fetch tools for MCP server UUID: ${in.uuid}: ${error}")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("TOOL_FETCH_FAILED", s"Failed to fetch tools: ${error}").build())
      }
    }
  }

  /**
   * Unload tools from a downstream MCP server and remove them from the user's MCP server actor.
   *
   * @param in       UnloadToolsForMcpServerRequest containing the uuid of the downstream MCP server
   * @param metadata Request metadata containing authentication info
   * @return Future[Empty] on successful tool unloading
   */
  override def unloadToolsForMcpServer(
    in: UnloadToolsForMcpServerRequest,
    metadata: Metadata
  ): Future[Empty] = {
    // Validate request and unload tools
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received UnloadToolsForMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      // Fetch the tool specifications for the given MCP server UUID
      toolsResult <- ToolsService.getInstance(authContext.userId).getDownstreamToolsSpec(in.uuid)
    } yield {
      toolsResult match {
        case Right(toolSpecs) =>
          // Extract tool names from specifications
          val toolNames = toolSpecs.map(_.tool().name())
          logger.info(s"Successfully fetched ${toolNames.size} tool names for removal from MCP server UUID: ${in.uuid}")

          // Send RemoveTools message to the actor (fire and forget)
          ActorLookup.getMcpServerActor(McpServerActorId(authContext.userId)) ! McpServerActor.RemoveTools(toolNames)

          logger.info(s"Sent ${toolNames.size} tool names for removal to MCP server actor for user ${authContext.userId}")
          Empty()

        case Left(error) =>
          logger.error(s"Failed to fetch tools for MCP server UUID: ${in.uuid}: ${error}")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("TOOL_FETCH_FAILED", s"Failed to fetch tools: ${error}").build())
      }
    }
  }

  /**
   * Get event logs with optional filtering and pagination.
   *
   * @param in       GetEventLogRequest containing filter criteria and pagination info
   * @param metadata Request metadata containing authentication info
   * @return Future[EventLogList] containing list of event logs
   */
  override def getEventLog(
    in: GetEventLogRequest,
    metadata: Metadata
  ): Future[EventLogList] = {
    // Validate request and fetch event logs
    for {
      authContext <- ServerAuthService.authenticate(metadata, authEnabled)
      _ = logger.info(s"Received GetEventLog request from userId: ${authContext.userId}, cursor: ${in.cursor}, pageSize: ${in.pageSize}")

      // Parse filter parameters
      filter = in.filter.getOrElse(Filter())
      eventType = if (filter.eventType.isEmpty) None else Some(filter.eventType)

      // Determine page size (default: 100, max: 1000)
      pageSize = if (in.pageSize <= 0) 100 else Math.min(in.pageSize, 1000)

      // Parse cursor (timestamp in milliseconds)
      cursorTimestamp = if (in.cursor.isEmpty) { None } else { Some(new java.sql.Timestamp(in.cursor.toLong))}

      // Fetch event logs from database
      listResult <- DbInterface.listEventLogs(
        tenant = authContext.tenant,
        userId = Some(authContext.userId),
        eventType = eventType,
        cursorTimestamp = cursorTimestamp,
        pageSize = pageSize
      )
    } yield {
      listResult match {
        case Left(dbError) =>
          logger.error(s"Failed to fetch event logs: ${dbError.message}")
          throw new GrpcServiceException(Status.INTERNAL,
            MetadataBuilder().addText("DATABASE_ERROR", dbError.message).build())

        case Right(rows) =>
          // Convert rows to proto messages
          val eventLogs = rows.map(ConversionUtils.rowToEventLog)

          // Calculate next cursor (timestamp of last event)
          val nextCursor = if (rows.size >= pageSize) {
            rows.lastOption.map(_.createdAt.getTime.toString).getOrElse("")
          } else {
            "" // No more pages
          }

          logger.info(s"Returning ${eventLogs.size} event logs for userId: ${authContext.userId}, nextCursor: $nextCursor")
          EventLogList(
            eventLog = eventLogs,
            nextCursor = nextCursor
          )
      }
    }
  }

}
