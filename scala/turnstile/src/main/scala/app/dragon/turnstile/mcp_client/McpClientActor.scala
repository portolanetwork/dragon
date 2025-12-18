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

package app.dragon.turnstile.mcp_client

import app.dragon.turnstile.auth.ClientAuthService
import app.dragon.turnstile.db.{DbInterface, McpServerRow}
import app.dragon.turnstile.mcp_client.McpStreamingHttpAsyncClient
import app.dragon.turnstile.serializer.TurnstileSerializable
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * Entity ID for McpClientActor instances.
 *
 * Format: {userId}-{mcpClientActorId}
 * This allows for user-scoped client isolation when connecting to downstream MCP servers.
 *
 * @param userId The user identifier
 * @param mcpServerUuid The unique actor identifier (typically the downstream server UUID)
 */
case class McpClientActorId(
  userId: String, 
  mcpServerUuid: String
) {
  override def toString: String = s"$userId.$mcpServerUuid"
}

object McpClientActorId {
  /**
   * Parse an entity ID string into a McpClientActorId.
   *
   * @param entityId The entity ID string in format "userId-mcpClientActorId"
   * @return McpClientActorId instance
   */
  def fromString(
    entityId: String
  ): McpClientActorId = {
    val Array(userId, mcpServerUuid) = entityId.split("\\.", 2)

    McpClientActorId(userId, mcpServerUuid)
  }
}

/**
 * MCP Client Actor - manages a connection to a downstream MCP server.
 *
 * This actor wraps a McpStreamingHttpAsyncClient and provides actor-based
 * access to downstream MCP servers. It handles tool calls, resource access, and
 * notifications from the remote server.
 *
 * Architecture:
 * - Each registered downstream MCP server gets its own client actor instance
 * - Cluster sharding distributes actors across nodes
 * - Actor provides isolation and fault tolerance per downstream server
 * - Lazy initialization on first Initialize message
 *
 * Lifecycle States:
 * 1. initWaitState: Waiting for client initialization
 *    - Actor starts in this state
 *    - Initiates MCP handshake with downstream server
 *    - Stashes all messages until initialized
 * 2. activeState: Connected and ready
 *    - Handles tool calls, list operations, notifications
 *    - Full MCP protocol support
 *
 * Message Flow:
 * {{{
 * ToolsService → McpClientActor.McpListTools
 *   ↓
 * McpStreamingHttpAsyncClient.listTools()
 *   ↓
 * HTTP/SSE to downstream MCP server
 *   ↓
 * Reply with ListToolsResult
 * }}}
 *
 * Supported Operations:
 * - Tool calls (with streaming progress support)
 * - List tools/resources/prompts
 * - Read resources
 * - Get prompts
 * - Ping (health check)
 * - Notifications (tools changed, resources changed, logging, progress)
 *
 * Failure Handling:
 * - Connection failure → returns Left(ConnectionError)
 * - Operation failure → returns Left(ProcessingError)
 * - PostStop signal → closes client connection gracefully
 */
object McpClientActor {
  val TypeKey: EntityTypeKey[McpClientActor.Message] =
    EntityTypeKey[McpClientActor.Message]("McpClientActor")

  def initSharding(
    system: ActorSystem[?],
    db: Database
  ): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val mcpClientActorId = McpClientActorId.fromString(entityContext.entityId)

      McpClientActor(mcpClientActorId, db)
    })

  sealed trait Message extends TurnstileSerializable

  final case class McpToolCallRequest(
    request: McpSchema.CallToolRequest,
    replyTo: ActorRef[Either[McpClientError,
      McpSchema.CallToolResult]]
  ) extends Message

  final case class McpListTools(
    replyTo: ActorRef[Either[McpClientError, McpSchema.ListToolsResult]]
  ) extends Message

  final case class McpNotification(
    notificationType: NotificationType,
    payload: Any
  ) extends Message

  final case class McpPing(
    replyTo: ActorRef[Unit]
  ) extends Message

  sealed trait NotificationType extends TurnstileSerializable
  case object ToolsChanged extends NotificationType
  case object ResourcesChanged extends NotificationType
  case object PromptsChanged extends NotificationType
  case object LoggingMessage extends NotificationType
  case object ProgressUpdate extends NotificationType

  // Internal wrapper messages
  private final case class InitializeStatus(
    status: Either[McpClientError, McpStreamingHttpAsyncClient])
    extends Message

  private final case class PingResponse(
    result: Try[Unit],
    replyTo: ActorRef[Unit]
  ) extends Message

  private final case class ToolCallResponse(
    result: Try[McpSchema.CallToolResult],
    replyTo: ActorRef[Either[McpClientError,
      McpSchema.CallToolResult]]
  ) extends Message

  private final case class ListToolsResponse(
    result: Try[McpSchema.ListToolsResult],
    replyTo: ActorRef[Either[McpClientError,
      McpSchema.ListToolsResult]]
  ) extends Message

  sealed trait McpClientError extends TurnstileSerializable
  final case class DbError(message: String) extends McpClientError
  final case class ConnectionError(message: String) extends McpClientError
  final case class ProcessingError(message: String) extends McpClientError
  final case class NotInitializedError(message: String) extends McpClientError

  def apply(
    mcpClientActorId: McpClientActorId,
    db: Database,
    tenant: String = "default"
  ): Behavior[Message] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

        context.log.info(s"Creating MCP client actor for user ${mcpClientActorId.userId} connecting to server UUID ${mcpClientActorId.mcpServerUuid}")

        new McpClientActor(context, buffer, mcpClientActorId, db, tenant).initWaitState()
      }
    }
  }
}

class McpClientActor(
  context: ActorContext[McpClientActor.Message],
  buffer: StashBuffer[McpClientActor.Message],
  mcpClientActorId: McpClientActorId,
  db: Database,
  tenant: String = "default"
) {

  import McpClientActor.*

  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext
  implicit val database: Database = db
  implicit val system: ActorSystem[Nothing] = context.system

  val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  /**
   * State while waiting for client initialization.
   * Stashes incoming requests until the client is ready.
   */
  def initWaitState(
  ): Behavior[Message] = {
    context.pipeToSelf(initializeMcpClient(mcpClientActorId.mcpServerUuid)) {
      case Success(mcpClient: McpStreamingHttpAsyncClient) =>
        InitializeStatus(Right(mcpClient))
      case Failure(exception) =>
        context.log.error(s"Failed to initialize MCP client: ${exception.getMessage}")
        InitializeStatus(Left(ConnectionError(exception.getMessage)))
    }

    Behaviors.receiveMessagePartial(handleInitializeStatus())
  }

  /**
   * Active state - handles all MCP client operations.
   */
  def activeState(
    mcpClient: McpStreamingHttpAsyncClient
  ): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleToolCallRequest(mcpClient)
        .orElse(handleListTools(mcpClient))
        .orElse(handleNotification(mcpClient))
        .orElse(handlePing(mcpClient))
        .orElse(handlePingResponse(mcpClient))
        .orElse(handleToolCallResponse(mcpClient))
        .orElse(handleListToolsResponse(mcpClient))
    }.receiveSignal {
      case (_, org.apache.pekko.actor.typed.PostStop) =>
        context.log.info(s"Stopping MCP client for actor $mcpClientActorId on PostStop")
        mcpClient.closeGracefully()
        Behaviors.same
    }
  }


  /**
   * Handle initialization status responses.
   * On success, transitions to active state and unstashes pending messages.
   * On failure, remains in init wait state (could add retry logic here).
   */
  def handleInitializeStatus(
  ): PartialFunction[Message, Behavior[Message]] = {
    case InitializeStatus(Right(mcpClient)) =>
      context.log.info(s"MCP client actor $mcpClientActorId initialized successfully")
      buffer.unstashAll(activeState(mcpClient))
    case InitializeStatus(Left(error)) =>
      context.log.error(s"MCP client actor $mcpClientActorId failed to initialize: $error")
      // TODO: Could implement retry logic here
      Behaviors.same
    case other =>
      context.log.debug(s"Stashing message while initializing: ${other.getClass.getSimpleName}")
      buffer.stash(other)
      Behaviors.same
  }

  /**
   * Handle ping messages - used as a liveness check.
   */
  def handlePing(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpPing(replyTo) =>
      context.pipeToSelf(mcpClient.ping()) {
        case Success(_) =>
          context.log.info(s"Ping succeeded")
          PingResponse(Success(()), replyTo)
        case Failure(exception) =>
          context.log.error(s"Ping failed: ${exception.getMessage}")
          PingResponse(Failure(exception), replyTo)
      }

      activeState(mcpClient)
  }

  /**
   * Handle ping responses produced by the internal ping pipeToSelf.
   */
  def handlePingResponse(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case PingResponse(result, replyTo) =>
      result match {
        case Success(_) =>
          replyTo ! ()
        case Failure(exception) =>
          context.log.error(s"Ping operation failed: ${exception.getMessage}")
          replyTo ! ()
      }

      activeState(mcpClient)
  }

  /**
   * Handle tool call requests.
   * Ensures progress token is set before forwarding to the MCP client.
   */
  def handleToolCallRequest(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpToolCallRequest(request: McpSchema.CallToolRequest, replyTo) =>
      context.log.info(s"Calling tool '${request.name()}' with arguments: ${request.arguments()}")

      // Ensure progress token is set - some servers require non-null tokens
      if (request.progressToken() == null)
        request.meta().put("progressToken", UUID.randomUUID().toString)

      context.pipeToSelf(mcpClient.callTool(request)) {
        case Success(result) => ToolCallResponse(Success(result), replyTo)
        case Failure(exception) => ToolCallResponse(Failure(exception), replyTo)
      }

      activeState(mcpClient)
  }

  /**
   * Handle list tools requests.
   */
  def handleListTools(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpListTools(replyTo) =>
      context.log.info(s"MCP Client Actor $mcpClientActorId listing tools")

      context.pipeToSelf(mcpClient.listTools()) {
        case Success(result) => ListToolsResponse(Success(result), replyTo)
        case Failure(exception) => ListToolsResponse(Failure(exception), replyTo)
      }

      activeState(mcpClient)
  }

  /**
   * Handle notifications from the MCP server.
   * These are received through the client's notification consumers.
   */
  def handleNotification(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpNotification(notificationType, payload) =>
      notificationType match {
        case ToolsChanged =>
          context.log.info(s"[NOTIFICATION] Tools changed: $payload")

        case ResourcesChanged =>
          context.log.info(s"[NOTIFICATION] Resources changed: $payload")

        case PromptsChanged =>
          context.log.info(s"[NOTIFICATION] Prompts changed: $payload")

        case LoggingMessage =>
          context.log.info(s"[SERVER LOG] $payload")

        case ProgressUpdate =>
          context.log.debug(s"[PROGRESS] $payload")
      }

      activeState(mcpClient)
  }

  /**
   * Handle wrapped tool call responses.
   */
  def handleToolCallResponse(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case ToolCallResponse(result, replyTo) =>
      result match {
        case Success(toolResult) =>
          context.log.info(s"Tool call succeeded")
          replyTo ! Right(toolResult)

        case Failure(exception) =>
          context.log.error(s"Tool call failed: ${exception.getMessage}")
          replyTo ! Left(ProcessingError(exception.getMessage))
      }

      activeState(mcpClient)
  }

  /**
   * Handle wrapped list tools responses.
   */
  def handleListToolsResponse(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case ListToolsResponse(result, replyTo) =>
      result match {
        case Success(listResult) =>
          context.log.info(s"List tools succeeded: ${listResult.tools().size()} tools")
          replyTo ! Right(listResult)

        case Failure(exception) =>
          context.log.error(s"List tools failed: ${exception.getMessage}")
          replyTo ! Left(ProcessingError(exception.getMessage))
      }

      activeState(mcpClient)
  }

  /**
   * Create an access token provider for authentication with the MCP server.
   * Returns a function that fetches auth tokens on-demand via ClientAuthService.
   *
   * @param mcpServerUuid The MCP server UUID for which to fetch tokens
   * @return Optional token provider function
   */
  private def getAccessToken(
    mcpServerUuid: String
  ): Option[() => Future[String]] = {
    Some(() => {
      ClientAuthService.getAuthTokenCached(tenant, mcpClientActorId.userId, mcpServerUuid).map {
        case Left(error) =>
          throw new RuntimeException(s"Failed to get access token: ${error.toString}")
        case Right(authToken) =>
          authToken.accessToken
      }
    })
  }

  /**
   * Initialize the MCP client connection.
   *
   * Performs the following:
   * 1. Fetches server configuration from database
   * 2. Sets up authentication provider based on auth type
   * 3. Creates HTTP streaming client
   * 4. Executes MCP initialize handshake
   *
   * @param mcpServerUuid The UUID of the MCP server to connect to
   * @return Future containing the initialized MCP client
   */
  private def initializeMcpClient(
    mcpServerUuid: String,
  ): Future[McpStreamingHttpAsyncClient] = {
    for {
      mcpServerRow: McpServerRow <- DbInterface.findMcpServerByUuid(tenant, mcpClientActorId.userId, UUID.fromString(mcpServerUuid)).flatMap {
        case Right(row) => Future.successful(row)
        case Left(dbError) => Future.failed(new RuntimeException(s"Database error: ${dbError.message}"))
      }
      authTokenProvider: Option[() => Future[String]] = mcpServerRow.authType match {
        case "none" => None
        case "discover" => getAccessToken(mcpServerRow.uuid.toString)
        case "static_auth_header" => None // Note: static auth header support not yet implemented
        case other => None
      }

      _ = logger.info(s"Initializing MCP client actor $mcpClientActorId with server URL $mcpServerRow.url")
      _ = logger.info(s"Auth token provider is ${if (authTokenProvider.isDefined) "defined" else "not defined"}")

      mcpClient = McpStreamingHttpAsyncClient(
        serverUrl = mcpServerRow.url,
        authTokenProvider = authTokenProvider
      )
      initResult: McpSchema.InitializeResult <- mcpClient.initialize()
      _ = logger.info(s"MCP client initialized: ${initResult}")
    } yield mcpClient

  }
}
