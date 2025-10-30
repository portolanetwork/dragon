package app.dragon.turnstile.actor

import app.dragon.turnstile.examples.{PekkoToSpringRequestAdapter, SpringToPekkoResponseAdapter, TurnstileMcpServer}
import com.google.rpc.context.AttributeContext.Response
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.jdk.FutureConverters.*

case class McpActorId(userId: String, mcpActorId: String) {
  override def toString: String = s"$userId-$mcpActorId"
}

object McpActorId {
  def fromString(entityId: String): McpActorId = {
    val Array(userId, mcpActorId) = entityId.split("-", 2)
    McpActorId(userId, mcpActorId)
  }
}

object McpActor {
  val TypeKey: EntityTypeKey[McpActor.Message] =
    EntityTypeKey[McpActor.Message]("McpActor")

  def initSharding(system: ActorSystem[_]): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val id = McpActorId.fromString(entityContext.entityId)

      McpActor(id.userId, id.mcpActorId)
    })

  def getEntityId(userId: String, chatId: String): String =
    s"$userId-$chatId"

  sealed trait Message extends TurnstileSerializable
  final case class McpGetRequest(request: HttpRequest, replyTo: ActorRef[Either[McpActorError, HttpResponse]]) extends Message
  final case class McpPostRequest(request: HttpRequest, replyTo: ActorRef[Either[McpActorError, HttpResponse]]) extends Message
  final case class McpDeleteRequest(request: HttpRequest, replyTo: ActorRef[Either[McpActorError, HttpResponse]]) extends Message

  sealed trait McpActorError extends TurnstileSerializable
  final case class ProcessingError(message: String) extends McpActorError


  def apply(userId: String, mcpActorId: String): Behavior[Message] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        new McpActor(context, buffer, userId, mcpActorId).activeState(TurnstileMcpServer("exampleServer", "1.0.0", "default").start())
      }
    }
  }
}

class McpActor(
  context: ActorContext[McpActor.Message],
  buffer: StashBuffer[McpActor.Message],
  userId: String,
  mcpActorId: String,
) {
  import McpActor._

  // Provide required implicits for adapters
  implicit val system: ActorSystem[?] = context.system
  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

  def activeState(
    turnstileMcpServer: TurnstileMcpServer
  ): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleMcpGetRequest(turnstileMcpServer)
        .orElse(handleMcpPostRequest(turnstileMcpServer))
        .orElse(handleMcpDeleteRequest(turnstileMcpServer))
    }
  }

  def handleMcpGetRequest(
    turnstileMcpServer: TurnstileMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpGetRequest(request, replyTo) =>
      context.log.info(s"MCP Actor $mcpActorId handling GET request")
      
      handlePekkoRequest(request, turnstileMcpServer).onComplete {
        case scala.util.Success(response) =>
          replyTo ! Right(response)
        case scala.util.Failure(exception) =>
          replyTo ! Left(ProcessingError(exception.getMessage))
      }

      activeState(turnstileMcpServer)
  }

  def handleMcpPostRequest(
    turnstileMcpServer: TurnstileMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpPostRequest(request, replyTo) =>
      context.log.info(s"Handling MCP POST request for user $userId, actor $mcpActorId")

      handlePekkoRequest(request, turnstileMcpServer).onComplete {
        case scala.util.Success(response) =>
          replyTo ! Right(response)
        case scala.util.Failure(exception) =>
          replyTo ! Left(ProcessingError(exception.getMessage))
      }

      activeState(turnstileMcpServer)
  }

  def handleMcpDeleteRequest(
    turnstileMcpServer: TurnstileMcpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpDeleteRequest(request, replyTo) =>
      context.log.info(s"Handling MCP DELETE request: ${request.uri}")

      handlePekkoRequest(request, turnstileMcpServer).onComplete {
        case scala.util.Success(response) =>
          replyTo ! Right(response)
        case scala.util.Failure(exception) =>
          replyTo ! Left(ProcessingError(exception.getMessage))
      }

      activeState(turnstileMcpServer)
  }

  private def handlePekkoRequest(
    request: HttpRequest,
    turnstileMcpServer: TurnstileMcpServer
  ): Future[HttpResponse] = {
    val webFluxRequest = PekkoToSpringRequestAdapter(request)
    val webFluxResponse = SpringToPekkoResponseAdapter()
    // Call the WebFlux handler (returns a Mono[Void])
    val handlerMono = turnstileMcpServer.getHttpHandler.handle(webFluxRequest, webFluxResponse)
    // Convert Mono[Void] to Scala Future[HttpResponse]
    handlerMono.toFuture.asScala.flatMap(_ => webFluxResponse.getPekkoResponse())
  }
}