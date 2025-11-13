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
import app.dragon.turnstile.mcp_client.{McpClientActor, McpClientActorId, McpStreamingHttpAsyncClient}
import app.dragon.turnstile.serializer.TurnstileSerializable
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import slick.jdbc.JdbcBackend.Database

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
    val Array(userId, mcpServerUuid) = entityId.split("-", 2)
    McpClientActorId(userId, mcpServerUuid)
  }

  /**
   * Construct an entity ID string from components.
   *
   * @param userId The user identifier
   * @param mcpServerUuid The client identifier
   * @return Entity ID string
   */
  def getEntityId(
    userId: String,
    mcpServerUuid: String
  ): String =
    s"$userId-$mcpServerUuid"
}

/**
 * MCP Client Actor - manages a connection to a downstream MCP server.
 *
 * This actor wraps a TurnstileStreamingHttpAsyncMcpClient and provides actor-based
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
 * 1. initWaitState: Waiting for Initialize message with server URL
 *    - Actor starts in this state
 *    - Stashes all messages until initialized
 * 2. initializingState: Connecting to downstream server
 *    - MCP handshake in progress
 *    - Messages still stashed
 * 3. activeState: Connected and ready
 *    - Handles tool calls, list operations, notifications
 *    - Full MCP protocol support
 *
 * Message Flow:
 * {{{
 * ToolsService → McpClientActor.McpListTools
 *   ↓
 * TurnstileStreamingHttpAsyncMcpClient.listTools()
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
      val id = McpClientActorId.fromString(entityContext.entityId)

      McpClientActor(id.userId, id.mcpServerUuid, db)
    })

  sealed trait Message extends TurnstileSerializable

  final case class Initialize(
    mcpServerUuid: String,
    mcpServerUrl: String
  ) extends Message

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
    status: Either[McpClientError,
      McpSchema.InitializeResult])
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
  final case class ConnectionError(message: String) extends McpClientError
  final case class ProcessingError(message: String) extends McpClientError
  final case class NotInitializedError(message: String) extends McpClientError

  def apply(
    userId: String,
    mcpClientActorId: String,
    db: Database
  ): Behavior[Message] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

        context.log.info(s"Creating MCP client actor for user $userId, client $mcpClientActorId")

        new McpClientActor(context, buffer, userId, mcpClientActorId, db).initWaitState()
      }
    }
  }
}

class McpClientActor(
  context: ActorContext[McpClientActor.Message],
  buffer: StashBuffer[McpClientActor.Message],
  userId: String,
  mcpClientActorId: String,
  db: Database
) {

  import McpClientActor.*

  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext
  implicit val database: Database = db
  implicit val system: ActorSystem[Nothing] = context.system

  /**
   * State while waiting for client initialization.
   * Stashes incoming requests until the client is ready.
   */
  def initWaitState(
  ): Behavior[Message] = {
    Behaviors.receiveMessagePartial(
      handleInitialize()
    )
  }

  def initializingState(
    mcpClient: McpStreamingHttpAsyncClient
  ): Behavior[Message] = {
    Behaviors.receiveMessagePartial(
      handleInitializeStatus(mcpClient)
    )
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

  def handleInitialize(
  ): PartialFunction[Message, Behavior[Message]] = {
    case Initialize(mcpServerUuid, mcpServerUrl) =>
      val authTokenProvider: Option[() => Future[String]] = getAccessToken(mcpServerUuid)
      
      val authInfo = if (authTokenProvider.isDefined) "with authentication" else "without authentication"
      context.log.info(s"Initializing MCP client actor $mcpClientActorId with server URL $mcpServerUrl $authInfo")

      val mcpClient = McpStreamingHttpAsyncClient(
        serverUrl = mcpServerUrl,
        authTokenProvider = authTokenProvider
      )

      context.pipeToSelf(mcpClient.initialize()) {
        case Success(initResult) =>
          context.log.info(s"MCP client initialized: ${initResult.serverInfo().name()}")
          InitializeStatus(Right(initResult))
        case Failure(exception) =>
          context.log.error(s"Failed to initialize MCP client: ${exception.getMessage}")
          InitializeStatus(Left(ConnectionError(exception.getMessage)))
      }

      initializingState(mcpClient)
  }

  /**
   * Handles the initialization of the MCP client and pipes the result to self.
   */
  def handleInitializeStatus(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case InitializeStatus(status) =>
      context.log.info(s"MCP client actor $mcpClientActorId initialized successfully")
      buffer.unstashAll(activeState(mcpClient))
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
      // reply with Unit to indicate liveness
      context.pipeToSelf(mcpClient.ping()) {
        case Success(_) =>
          context.log.info(s"Ping succeeded")
          // wrap a successful Unit result
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
          // Notify caller that ping succeeded
          replyTo ! ()
        case Failure(exception) =>
          // On failure, still reply Unit but log the error
          context.log.error(s"Ping operation failed when responding: ${exception.getMessage}")
          replyTo ! ()
      }

      activeState(mcpClient)
  }

  /**
   * Handle tool call requests.
   */
  def handleToolCallRequest(
    mcpClient: McpStreamingHttpAsyncClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpToolCallRequest(request, replyTo) =>
      context.log.info(s"MCP Client Actor $mcpClientActorId calling tool: ${request.name()}")

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

  private def getAccessToken(
    mcpServerUuid: String
  ): Option[() => Future[String]] = {
    Some(() => {
      ClientAuthService.getAuthToken(mcpServerUuid).map {
        case Left(error) =>
          // Log error and throw exception
          // The asyncHttpRequestCustomizer will catch this and log it
          throw new RuntimeException(s"Failed to get access token: ${error.toString}")
        case Right(authToken) =>
          authToken.accessToken
      }
    })
  }
}
