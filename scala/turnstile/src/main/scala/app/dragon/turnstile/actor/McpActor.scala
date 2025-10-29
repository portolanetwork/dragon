package app.dragon.turnstile.actor

import com.google.rpc.context.AttributeContext.Response
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.collection.mutable.ArrayBuffer

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
    EntityTypeKey[McpActor.Message]("ChatActor")

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
        new McpActor(context, buffer, userId, mcpActorId).activeState()
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

  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  def activeState(): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleMcpGetRequest()
        .orElse(handleMcpPostRequest())
        .orElse(handleMcpDeleteRequest())
    }
  }
  
  def handleMcpGetRequest(): PartialFunction[Message, Behavior[Message]] = {
    case McpGetRequest(request, replyTo) =>
      Behaviors.same
  }
  
  def handleMcpPostRequest(): PartialFunction[Message, Behavior[Message]] = {
    case McpPostRequest(request, replyTo) =>
      Behaviors.same
  }
  
  def handleMcpDeleteRequest(): PartialFunction[Message, Behavior[Message]] = {
    case McpDeleteRequest(request, replyTo) =>
      Behaviors.same
  }
}