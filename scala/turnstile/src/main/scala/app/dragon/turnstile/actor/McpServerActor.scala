package app.dragon.turnstile.actor

import app.dragon.turnstile.actor.McpClientActor.McpListTools
import app.dragon.turnstile.server.{PekkoToSpringRequestAdapter, SpringToPekkoResponseAdapter, TurnstileStreamingHttpMcpServer}
import com.google.rpc.context.AttributeContext.Response
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import reactor.core.scheduler.Schedulers

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.jdk.FutureConverters.*

case class McpServerActorId(userId: String, mcpServerActorId: String) {
  override def toString: String = s"$userId-$mcpServerActorId"
}

object McpServerActorId {
  def fromString(entityId: String): McpServerActorId = {
    val Array(userId, mcpActorId) = entityId.split("-", 2)
    McpServerActorId(userId, mcpActorId)
  }
}

object McpServerActor {
  val TypeKey: EntityTypeKey[McpServerActor.Message] =
    EntityTypeKey[McpServerActor.Message]("McpActor")

  def initSharding(system: ActorSystem[_]): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val id = McpServerActorId.fromString(entityContext.entityId)

      McpServerActor(id.userId, id.mcpServerActorId)
    })

  def getEntityId(userId: String, chatId: String): String =
    s"$userId-$chatId"

  sealed trait Message extends TurnstileSerializable
  final case class McpGetRequest(
    request: HttpRequest, 
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

  def apply(userId: String, mcpActorId: String): Behavior[Message] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        // Fix: instantiate the class with 'new' instead of as a function
        val turnstileMcpServer = TurnstileStreamingHttpMcpServer().start()

        new McpServerActor(context, buffer, userId, mcpActorId).activeState(turnstileMcpServer)
      }
    }
  }
}

class McpServerActor(
  context: ActorContext[McpServerActor.Message],
  buffer: StashBuffer[McpServerActor.Message],
  userId: String,
  mcpActorId: String,
) {
  import McpServerActor._

  // Provide required implicits for adapters
  implicit val system: ActorSystem[?] = context.system
  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

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
        context.log.info(s"Stopping MCP server for actor $mcpActorId on PostStop")
        turnstileMcpServer.stop()
        Behaviors.same
    }
  }

  def handleMcpGetRequest(
    turnstileMcpServer: TurnstileStreamingHttpMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpGetRequest(request, replyTo) =>
      context.log.info(s"MCP Actor $mcpActorId handling GET request")
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
      context.log.info(s"Handling MCP POST request for user $userId, actor $mcpActorId")

      // Provide the ClusterSharding instance explicitly to satisfy the implicit parameter
      ActorLookup.getMcpClientActor("client-actor")(ClusterSharding(context.system)) ! McpListTools(context.system.ignoreRef)

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