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
object MgmtServiceImpl {
  // Placeholder for future companion object utilities
  def apply()(
    implicit db: Database,
    system: org.apache.pekko.actor.typed.ActorSystem[?],
    sharding: org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
  ): MgmtServiceImpl =
    new MgmtServiceImpl()
}

/**
 * Implementation of the TurnstileService gRPC service.
 *
 * This service provides MCP server registration functionality through gRPC.
 */
class MgmtServiceImpl()(
  implicit db: Database,
  system: org.apache.pekko.actor.typed.ActorSystem[?],
  sharding: org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
) extends TurnstileServicePowerApi {
  private val logger: Logger = LoggerFactory.getLogger(classOf[MgmtServiceImpl])

  implicit private val ec: ExecutionContext = ExecutionContext.global

  /**
   * Converts a database authType string to proto AuthType enum.
   */
  private def stringToAuthType(authType: String): AuthType = authType.toLowerCase match {
    case "none" => AuthType.AUTH_TYPE_NONE
    case "discover" => AuthType.AUTH_TYPE_DISCOVER
    case "static_auth_header" => AuthType.AUTH_TYPE_STATIC_HEADER
    case _ => AuthType.AUTH_TYPE_UNSPECIFIED
  }

  /**
   * Converts a proto AuthType enum to database authType string.
   */
  private def authTypeToString(authType: AuthType): String = authType match {
    case AuthType.AUTH_TYPE_NONE => "none"
    case AuthType.AUTH_TYPE_DISCOVER => "discover"
    case AuthType.AUTH_TYPE_STATIC_HEADER => "static_auth_header"
    case AuthType.AUTH_TYPE_UNSPECIFIED | AuthType.Unrecognized(_) => "none"
  }

  /**
   * Converts a database row to a McpServer proto message.
   */
  private def rowToMcpServer(row: McpServerRow): McpServer = {
    val authType = stringToAuthType(row.authType)

    // Build OAuth config if auth type is DISCOVER and OAuth fields are present
    val oauthConfig = if (authType == AuthType.AUTH_TYPE_DISCOVER) {
      Some(OAuthConfig(
        clientId = row.clientId.getOrElse(""),
        tokenEndpoint = row.tokenEndpoint.getOrElse(""),
        hasClientSecret = row.clientSecret.isDefined,
        hasRefreshToken = row.refreshToken.isDefined
      ))
    } else {
      None
    }

    McpServer(
      uuid = row.uuid.toString,
      name = row.name,
      url = row.url,
      authType = authType,
      hasStaticToken = row.staticToken.isDefined,
      oauthConfig = oauthConfig,
      createdAt = Some(com.google.protobuf.timestamp.Timestamp(
        seconds = row.createdAt.getTime / 1000,
        nanos = ((row.createdAt.getTime % 1000) * 1000000).toInt
      )),
      updatedAt = Some(com.google.protobuf.timestamp.Timestamp(
        seconds = row.updatedAt.getTime / 1000,
        nanos = ((row.updatedAt.getTime % 1000) * 1000000).toInt
      ))
    )
  }

  /**
   * Create a new MCP server registration.
   *
   * @param in CreateMcpServerRequest containing name and url
   * @return Future[McpServer] containing the created MCP server info
   */
  override def createMcpServer(
    in: CreateMcpServerRequest,
    metadata: Metadata,
  ): Future[McpServer] = {
    val now = Timestamp(System.currentTimeMillis())

    // Validate request using generic utility
    for {
      authContext <- ServerAuthService.authenticate(metadata)
      //_ = logger.info(s"Received CreateMcpServer request for name: ${request.name}, url: ${request.url} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.name, "name")
      _ <- validateHasNoSpaces(in.name, "name")
      _ <- validateNotEmpty(in.url, "url")
      insertResult <- DbInterface.insertMcpServer(McpServerRow(
        tenant = authContext.tenant,
        userId = authContext.userId,
        name = in.name,
        url = in.url,
        authType = authTypeToString(in.authType),
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

          // TODO: ToolServer must pick up new server registration at runtime and start using it

          rowToMcpServer(row)
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
      authContext <- ServerAuthService.authenticate(metadata)
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
          val servers = rows.map(rowToMcpServer)
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
  override def deleteMcpServer(
    in: DeleteMcpServerRequest,
    metadata: Metadata,
  ): Future[Empty] = {
    // Validate request and delete server
    for {
      authContext <- ServerAuthService.authenticate(metadata)
      _ = logger.info(s"Received DeleteMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      uuid <- Future.fromTry(scala.util.Try(UUID.fromString(in.uuid)))
        .recoverWith(_ => Future.failed(new GrpcServiceException(Status.INVALID_ARGUMENT,
          MetadataBuilder().addText("INVALID_UUID", s"Invalid UUID format: ${in.uuid}").build())))
      deleteResult <- DbInterface.deleteMcpServerByUuid(uuid)
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
   * @param in LoginMcpServerRequest containing the uuid
   * @param metadata Request metadata containing authentication info
   * @return Future[McpServerLoginUrl] containing the login URL to redirect to
   */
  override def loginToMcpServer(
    in: LoginMcpServerRequest,
    metadata: Metadata
  ): Future[McpServerLoginUrl] = {
    // Validate request and initiate auth flow
    for {
      authContext <- ServerAuthService.authenticate(metadata)
      _ = logger.info(s"Received LoginToMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      loginUrlResult <- ClientAuthService.initiateAuthCodeFlow(in.uuid)
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
      authContext <- ServerAuthService.authenticate(metadata)
      _ = logger.info(s"Received GetLoginStatusForMcpServer request for uuid: ${in.uuid} from userId: ${authContext.userId}")
      _ <- validateNotEmpty(in.uuid, "uuid")
      loginStatusResult <- ClientAuthService.getMcpServerLoginStatus(in.uuid)
    } yield {
      loginStatusResult match {
        case Right(isLoggedIn) =>
          logger.info(s"Retrieved login status for MCP server UUID: ${in.uuid}, isLoggedIn: $isLoggedIn")
          McpServerLoginStatus(isLoggedIn = isLoggedIn)
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
}
