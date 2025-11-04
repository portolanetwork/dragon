package app.dragon.turnstile.actor

import app.dragon.turnstile.server.{PekkoToSpringRequestAdapter, SpringToPekkoResponseAdapter, TurnstileStreamingHttpMcpServer}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import reactor.core.scheduler.Schedulers

import scala.concurrent.Future
import scala.jdk.FutureConverters.*

/**
 * Entity ID for McpServerActor instances.
 *
 * Format: {userId}-{mcpServerActorId}
 * This allows for user-scoped MCP server isolation within the cluster.
 *
 * @param userId The user identifier
 * @param mcpServerActorId The unique actor identifier for this user's MCP server
 */
case class McpServerActorId(userId: String, mcpServerActorId: String) {
  override def toString: String = s"$userId-$mcpServerActorId"
}

object McpServerActorId {
  /**
   * Parse an entity ID string into a McpServerActorId.
   *
   * @param entityId The entity ID string in format "userId-mcpServerActorId"
   * @return McpServerActorId instance
   */
  def fromString(entityId: String): McpServerActorId = {
    val Array(userId, mcpServerActorId) = entityId.split("-", 2)
    McpServerActorId(userId, mcpServerActorId)
  }
}

/**
 * MCP Server Actor - manages a user-scoped MCP server instance.
 *
 * This actor encapsulates a TurnstileStreamingHttpMcpServer (Spring WebFlux-based MCP server)
 * and handles HTTP requests from the gateway by adapting between Pekko and Spring types.
 *
 * Architecture:
 * - Each user can have their own MCP server instance(s)
 * - Cluster sharding ensures actors are distributed across nodes
 * - Actor isolates user state and provides session affinity
 * - Adapters bridge Pekko HTTP ↔ Spring WebFlux
 *
 * Lifecycle States:
 * 1. initState: Waiting for MCP server initialization and downstream tool refresh
 *    - Stashes incoming requests
 *    - Fetches tools from registered downstream MCP servers
 * 2. activeState: Handling HTTP requests
 *    - Processes GET, POST, DELETE requests
 *    - Adapts Pekko requests to Spring WebFlux
 *    - Converts Spring responses back to Pekko
 *
 * Message Flow:
 * {{{
 * Gateway → McpServerActor.McpPostRequest(pekkoRequest)
 *   ↓
 * PekkoToSpringRequestAdapter (converts HttpRequest → ServerHttpRequest)
 *   ↓
 * TurnstileStreamingHttpMcpServer.getHttpHandler (Spring WebFlux)
 *   ↓
 * SpringToPekkoResponseAdapter (converts ServerHttpResponse → HttpResponse)
 *   ↓
 * Reply to Gateway with HttpResponse
 * }}}
 *
 * Failure Handling:
 * - Initialization failure → transitions to active state anyway (graceful degradation)
 * - Request processing errors → returns Left(ProcessingError) to caller
 * - PostStop signal → stops embedded MCP server cleanly
 */
object McpServerActor {
  val TypeKey: EntityTypeKey[McpServerActor.Message] =
    EntityTypeKey[McpServerActor.Message]("McpServerActor")

  def initSharding(system: ActorSystem[?]): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val id = McpServerActorId.fromString(entityContext.entityId)

      McpServerActor(id.userId, id.mcpServerActorId)
    })

  def getEntityId(userId: String, chatId: String): String =
    s"$userId-$chatId"

  sealed trait Message extends TurnstileSerializable

  final case class McpGetRequest(request: HttpRequest,
    replyTo: ActorRef[Either[McpActorError, HttpResponse]])
    extends Message

  final case class McpPostRequest(
    request: HttpRequest,
    replyTo: ActorRef[Either[McpActorError, HttpResponse]])
    extends Message

  final case class McpDeleteRequest(
    request: HttpRequest,
    replyTo: ActorRef[Either[McpActorError, HttpResponse]])
    extends Message

  // Remove WrappedGetResponse, use only WrappedHttpResponse for all handlers
  private final case class WrappedHttpResponse(
    result: scala.util.Try[HttpResponse],
    replyTo: ActorRef[Either[McpActorError, HttpResponse]]
  ) extends Message

  sealed trait McpActorError extends TurnstileSerializable
  final case class ProcessingError(message: String) extends McpActorError

  // Internal message for server initialization
  private final case class DownstreamRefreshStatus(status: Either[McpActorError, Unit]) extends Message

  def apply(userId: String, mcpServerActorId: String): Behavior[Message] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

        context.log.info(s"Starting MCP server for user $userId, actor $mcpServerActorId")

        // Start the MCP server asynchronously
        val mcpSserver = TurnstileStreamingHttpMcpServer(userId).start()

        new McpServerActor(context, buffer, userId, mcpServerActorId).initState(mcpSserver)
      }
    }
  }
}

class McpServerActor(
  context: ActorContext[McpServerActor.Message],
  buffer: StashBuffer[McpServerActor.Message],
  userId: String,
  mcpServerActorId: String,
) {

  import McpServerActor.*

  // Provide required implicits for adapters
  implicit val system: ActorSystem[Nothing] = context.system
  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

  /**
   * State while waiting for the MCP server to start.
   * Stashes incoming requests until the server is ready.
   */
  def initState(
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): Behavior[Message] = {
    // Pipe the result to self
    context.pipeToSelf(turnstileMcpServer.refreshDownstreamTools()) {
      case scala.util.Success(_) => DownstreamRefreshStatus(Right(()))
      case scala.util.Failure(error) => DownstreamRefreshStatus(Left(ProcessingError(error.getMessage)))
    }

    Behaviors.receiveMessagePartial(handleDownstreamRefresh(turnstileMcpServer))
  }

  def activeState(
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleMcpGetRequest(turnstileMcpServer)
        .orElse(handleMcpPostRequest(turnstileMcpServer))
        .orElse(handleMcpDeleteRequest(turnstileMcpServer))
        .orElse(handleWrappedHttpResponse())
    }.receiveSignal {
      case (_, org.apache.pekko.actor.typed.PostStop) =>
        context.log.info(s"Stopping MCP server for actor $mcpServerActorId on PostStop")
        turnstileMcpServer.stop()
        Behaviors.same
    }
  }

  def handleDownstreamRefresh(
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case DownstreamRefreshStatus(Right(_)) =>
      context.log.info(s"MCP server for actor $mcpServerActorId downstream refresh succeeded, transitioning to active state")
      // Unstash all buffered messages and transition to active state
      buffer.unstashAll(activeState(turnstileMcpServer))
    case DownstreamRefreshStatus(Left(_)) =>
      context.log.error(s"MCP server for actor $mcpServerActorId downstream refresh failed, but continuing to active state")
      // Unstash all buffered messages and transition to active state anyway
      buffer.unstashAll(activeState(turnstileMcpServer))
    case other =>
      context.log.debug(s"Stashing message while MCP server is initializing: ${other.getClass.getSimpleName}")
      buffer.stash(other)
      Behaviors.same
  }

  def handleMcpGetRequest(
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpGetRequest(request, replyTo) =>
      context.log.info(s"MCP Actor $mcpServerActorId handling GET request")
      context.pipeToSelf(handlePekkoRequest(request, turnstileMcpServer)) {
        case scala.util.Success(response) => WrappedHttpResponse(scala.util.Success(response), replyTo)
        case scala.util.Failure(exception) => WrappedHttpResponse(scala.util.Failure(exception), replyTo)
      }
      Behaviors.same
  }

  def handleMcpPostRequest(
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpPostRequest(request, replyTo) =>
      context.log.info(s"Handling MCP POST request for user $userId, actor $mcpServerActorId")

      context.pipeToSelf(handlePekkoRequest(request, turnstileMcpServer)) {
        case scala.util.Success(response) => WrappedHttpResponse(scala.util.Success(response), replyTo)
        case scala.util.Failure(exception) => WrappedHttpResponse(scala.util.Failure(exception), replyTo)
      }
      Behaviors.same
  }

  def handleMcpDeleteRequest(
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpDeleteRequest(request, replyTo) =>
      context.log.info(s"Handling MCP DELETE request: ${request.uri}")
      context.pipeToSelf(handlePekkoRequest(request, turnstileMcpServer)) {
        case scala.util.Success(response) => WrappedHttpResponse(scala.util.Success(response), replyTo)
        case scala.util.Failure(exception) => WrappedHttpResponse(scala.util.Failure(exception), replyTo)
      }
      Behaviors.same
  }

  def handleWrappedHttpResponse(): PartialFunction[Message, Behavior[Message]] = {
    case WrappedHttpResponse(result, replyTo) =>
      result match {
        case scala.util.Success(response) =>
          replyTo ! Right(response)
        case scala.util.Failure(exception) =>
          replyTo ! Left(ProcessingError(exception.getMessage))
      }
      Behaviors.same
  }

  private def handlePekkoRequest(
    request: HttpRequest,
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): Future[HttpResponse] = {
    val springRequest = PekkoToSpringRequestAdapter(request)
    val springResponse = SpringToPekkoResponseAdapter()
    turnstileMcpServer.getHttpHandler match {
      case Some(handler) =>
        val handlerMono = handler.handle(springRequest, springResponse)
        handlerMono
          .subscribeOn(Schedulers.boundedElastic())
          .toFuture.asScala.flatMap { _ =>
            springResponse.getPekkoResponse()
          }
      case None =>
        Future.failed(new IllegalStateException("HttpHandler is not initialized in TurnstileStreamingHttpMcpServer"))
    }
  }
}