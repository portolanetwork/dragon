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

package app.dragon.turnstile.mcp_gateway

import app.dragon.turnstile.mcp_gateway.McpSessionMapActor
import app.dragon.turnstile.serializer.TurnstileSerializable
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}

/**
 * A simple sharded actor that stores a map of mcpSessionId -> mcpServerActorId for a given user.
 * Pattern and lifecycle mirror `McpServerActor` but the actor holds an in-memory Map state.
 */
object McpSessionMapActor {
  val TypeKey: EntityTypeKey[Message] = EntityTypeKey[Message]("McpSessionMapActor")

  def initSharding(
    system: ActorSystem[?]
  ): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val userId = entityContext.entityId
      McpSessionMapActor(userId)
    })

  def getEntityId(userId: String, sessionMapActorId: String): String = s"$userId-$sessionMapActorId"

  sealed trait Message extends TurnstileSerializable
  final case class SessionCreate(mcpSessionId: String, mcpServerActorId: String) extends Message
  final case class SessionDelete(mcpSessionId: String) extends Message
  final case class SessionLookup(mcpSessionId: String, replyTo: ActorRef[Either[SessionMapError, String]]) extends Message

  sealed trait SessionMapError extends TurnstileSerializable
  final case class NotFoundError(message: String) extends SessionMapError

  def apply(userId: String): Behavior[Message] =
    Behaviors.setup { context =>
      context.log.info(s"Starting McpSessionMapActor for user $userId")
      new McpSessionMapActor(context, userId).activeState(Map.empty)
    }
}

class McpSessionMapActor(
  context: ActorContext[McpSessionMapActor.Message],
  userId: String,
) {

  import McpSessionMapActor.*

  /**
   * activeState holds the in-memory mapping from session id -> server actor id.
   * All operations reply with Either[SessionMapError, ...] to match patterns used elsewhere.
   */
  def activeState(
    sessionIdToMcpActorIdMap: Map[String, String]
  ): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleSessionCreate(sessionIdToMcpActorIdMap)
        .orElse(handleSessionDelete(sessionIdToMcpActorIdMap))
        .orElse(handleSessionLookup(sessionIdToMcpActorIdMap))
    }
  }

  private def handleSessionCreate(
    sessionIdToMcpActorIdMap: Map[String, String]
  ): PartialFunction[Message, Behavior[Message]] = {
    case SessionCreate(sessionId, serverActorId) =>
      context.log.info(s"SessionCreate $sessionId -> $serverActorId for user $userId")
      if (sessionIdToMcpActorIdMap.contains(sessionId)) {
        context.log.info(s"Session $sessionId already exists for user $userId. Overwriting.")
        activeState(sessionIdToMcpActorIdMap + (sessionId -> serverActorId))
      } else {
        activeState(sessionIdToMcpActorIdMap + (sessionId -> serverActorId))
      }
  }

  private def handleSessionDelete(
    sesionIdToMcpActorIdMap: Map[String, String]
  ): PartialFunction[Message, Behavior[Message]] = {
    case SessionDelete(sessionId) =>
      context.log.info(s"SessionDelete $sessionId for user $userId")
      if (sesionIdToMcpActorIdMap.contains(sessionId)) {
        activeState(sesionIdToMcpActorIdMap - sessionId)
      } else {
        activeState(sesionIdToMcpActorIdMap)
      }
  }

  private def handleSessionLookup(
    sessionIdToMcpActorIdMap: Map[String, String]
  ): PartialFunction[Message, Behavior[Message]] = {
    case SessionLookup(sessionId, replyTo) =>
      context.log.info(s"SessionLookup $sessionId for user $userId")
      if (sessionIdToMcpActorIdMap.contains(sessionId)) {
        val actorId = sessionIdToMcpActorIdMap.getOrElse(sessionId, "unknown")
        context.log.info(s"Session $sessionId found for user $userId. ActorId: $actorId")
        replyTo ! Right(actorId)
        activeState(sessionIdToMcpActorIdMap)
      } else {
        context.log.info(s"Session $sessionId NOT found for user $userId")
        replyTo ! Left(NotFoundError(s"Session $sessionId not found"))
        activeState(sessionIdToMcpActorIdMap)
      }

  }
}

