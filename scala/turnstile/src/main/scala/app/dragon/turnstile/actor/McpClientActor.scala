package app.dragon.turnstile.actor

import app.dragon.turnstile.client.TurnstileStreamingHttpAsyncMcpClient
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}

import scala.util.{Failure, Success}

case class McpClientActorId(userId: String, mcpClientActorId: String) {
  override def toString: String = s"$userId-$mcpClientActorId"
}

object McpClientActorId {
  def fromString(entityId: String): McpClientActorId = {
    val Array(userId, mcpClientActorId) = entityId.split("-", 2)
    McpClientActorId(userId, mcpClientActorId)
  }
}

object McpClientActor {
  val TypeKey: EntityTypeKey[McpClientActor.Message] =
    EntityTypeKey[McpClientActor.Message]("McpClientActor")

  def initSharding(system: ActorSystem[?]): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val id = McpClientActorId.fromString(entityContext.entityId)

      McpClientActor(id.userId, id.mcpClientActorId)
    })

  def getEntityId(userId: String, clientId: String): String =
    s"$userId-$clientId"

  sealed trait Message extends TurnstileSerializable

  final case class McpToolCallRequest(
    request: McpSchema.CallToolRequest,
    replyTo: ActorRef[Either[McpClientError, McpSchema.CallToolResult]]
  ) extends Message

  final case class McpListTools(
    replyTo: ActorRef[Either[McpClientError, McpSchema.ListToolsResult]]
  ) extends Message

  final case class McpNotification(
    notificationType: NotificationType,
    payload: Any
  ) extends Message

  sealed trait NotificationType extends TurnstileSerializable
  case object ToolsChanged extends NotificationType
  case object ResourcesChanged extends NotificationType
  case object PromptsChanged extends NotificationType
  case object LoggingMessage extends NotificationType
  case object ProgressUpdate extends NotificationType

  // Internal wrapper messages
  private final case class InitializeStatus(status: Either[McpClientError, McpSchema.InitializeResult]) extends Message

  private final case class WrappedToolCallResponse(
    result: scala.util.Try[McpSchema.CallToolResult],
    replyTo: ActorRef[Either[McpClientError, McpSchema.CallToolResult]]
  ) extends Message

  private final case class WrappedListToolsResponse(
    result: scala.util.Try[McpSchema.ListToolsResult],
    replyTo: ActorRef[Either[McpClientError, McpSchema.ListToolsResult]]
  ) extends Message

  sealed trait McpClientError extends TurnstileSerializable
  final case class ConnectionError(message: String) extends McpClientError
  final case class ProcessingError(message: String) extends McpClientError
  final case class NotInitializedError(message: String) extends McpClientError

  def apply(userId: String, mcpClientActorId: String, serverUrl: String): Behavior[Message] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

        context.log.info(s"Creating MCP client actor for user $userId, client $mcpClientActorId")

        // Create the client
        val mcpClient = TurnstileStreamingHttpAsyncMcpClient("https://mcp.deepwiki.com/mcp")

        new McpClientActor(context, buffer, userId, mcpClientActorId).initState(mcpClient)
      }
    }
  }

  // Convenience apply without serverUrl for default configuration
  def apply(userId: String, mcpClientActorId: String): Behavior[Message] = {
    apply(userId, mcpClientActorId, "http://localhost:8081")
  }
}

class McpClientActor(
  context: ActorContext[McpClientActor.Message],
  buffer: StashBuffer[McpClientActor.Message],
  userId: String,
  mcpClientActorId: String,
) {

  import McpClientActor._

  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

  /**
   * State while waiting for client initialization.
   * Stashes incoming requests until the client is ready.
   */
  def initState(mcpClient: TurnstileStreamingHttpAsyncMcpClient): Behavior[Message] = {
    context.pipeToSelf(mcpClient.initialize()) {
      case Success(initResult) =>
        context.log.info(s"MCP client initialized: ${initResult.serverInfo().name()}")
        InitializeStatus(Right(initResult))
      case Failure(exception) =>
        context.log.error(s"Failed to initialize MCP client: ${exception.getMessage}")
        InitializeStatus(Left(ConnectionError(exception.getMessage)))
    }

    Behaviors.receiveMessagePartial(handleInitialize(mcpClient))
  }

  /**
   * Handles the initialization of the MCP client and pipes the result to self.
   */
  def handleInitialize(mcpClient: TurnstileStreamingHttpAsyncMcpClient): PartialFunction[Message, Behavior[Message]] = {
    case InitializeStatus(status) =>
      context.log.info(s"MCP client actor $mcpClientActorId initialized successfully")
      buffer.unstashAll(activeState(mcpClient))

    case other =>
      context.log.debug(s"Stashing message while initializing: ${other.getClass.getSimpleName}")
      buffer.stash(other)
      Behaviors.same
  }

  /**
   * Active state - handles all MCP client operations.
   */
  def activeState(mcpClient: TurnstileStreamingHttpAsyncMcpClient): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleToolCallRequest(mcpClient)
        .orElse(handleListTools(mcpClient))
        .orElse(handleNotification())
        .orElse(handleWrappedToolCallResponse())
        .orElse(handleWrappedListToolsResponse())
    }.receiveSignal {
      case (_, org.apache.pekko.actor.typed.PostStop) =>
        context.log.info(s"Stopping MCP client for actor $mcpClientActorId on PostStop")
        mcpClient.closeGracefully()
        Behaviors.same
    }
  }

  /**
   * Handle tool call requests.
   */
  def handleToolCallRequest(
    mcpClient: TurnstileStreamingHttpAsyncMcpClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpToolCallRequest(request, replyTo) =>
      context.log.info(s"MCP Client Actor $mcpClientActorId calling tool: ${request.name()}")

      context.pipeToSelf(mcpClient.callTool(request)) {
        case Success(result) => WrappedToolCallResponse(Success(result), replyTo)
        case Failure(exception) => WrappedToolCallResponse(Failure(exception), replyTo)
      }

      Behaviors.same
  }

  /**
   * Handle list tools requests.
   */
  def handleListTools(
    mcpClient: TurnstileStreamingHttpAsyncMcpClient
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpListTools(replyTo) =>
      context.log.info(s"MCP Client Actor $mcpClientActorId listing tools")

      context.pipeToSelf(mcpClient.listTools()) {
        case Success(result) => WrappedListToolsResponse(Success(result), replyTo)
        case Failure(exception) => WrappedListToolsResponse(Failure(exception), replyTo)
      }

      Behaviors.same
  }

  /**
   * Handle notifications from the MCP server.
   * These are received through the client's notification consumers.
   */
  def handleNotification(): PartialFunction[Message, Behavior[Message]] = {
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

      Behaviors.same
  }

  /**
   * Handle wrapped tool call responses.
   */
  def handleWrappedToolCallResponse(): PartialFunction[Message, Behavior[Message]] = {
    case WrappedToolCallResponse(result, replyTo) =>
      result match {
        case Success(toolResult) =>
          context.log.info(s"Tool call succeeded")
          replyTo ! Right(toolResult)

        case Failure(exception) =>
          context.log.error(s"Tool call failed: ${exception.getMessage}")
          replyTo ! Left(ProcessingError(exception.getMessage))
      }

      Behaviors.same
  }

  /**
   * Handle wrapped list tools responses.
   */
  def handleWrappedListToolsResponse(): PartialFunction[Message, Behavior[Message]] = {
    case WrappedListToolsResponse(result, replyTo) =>
      result match {
        case Success(listResult) =>
          context.log.info(s"List tools succeeded: ${listResult.tools().size()} tools")
          replyTo ! Right(listResult)

        case Failure(exception) =>
          context.log.error(s"List tools failed: ${exception.getMessage}")
          replyTo ! Left(ProcessingError(exception.getMessage))
      }

      Behaviors.same
  }
}
