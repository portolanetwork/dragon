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

import McpSessionMapActor.SessionMapError
import app.dragon.turnstile.utils.{ActorLookup, Random}

import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

// Added imports for ClusterSharding, ActorSystem and Timeout used by ask pattern
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.Timeout

/**
 * Simplified router: routes HTTP requests to WebFlux handlers based on a header value.
 *
 * Looks up the handler ID directly from the specified header value.
 * If not found, optionally falls back to the default handler.
 */
class SessionMap()(
  implicit ec: ExecutionContext,
  system: ActorSystem[?],
  sharding: ClusterSharding, timeout: Timeout
) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[SessionMap])

  case class ActiveSessionResult(mcpActorId: String, sessionIdOpt: Option[String])

  final val mcpSessionHeader = "mcp-session-id"
  final val authHeader = "authorization"
  
  /**
   * Route a Pekko HTTP request to the appropriate WebFlux handler.
   *
   * @param pekkoRequest The incoming Pekko HTTP request
   * @return Future[Either[HttpResponse, (String, HttpHandler)]]
   */
  def lookup(
    pekkoRequest: HttpRequest
  ): Future[ActiveSessionResult] = {
    // Extract mcp-session-id header value
    
    val sessionIdOpt = pekkoRequest.headers
      .find(_.lowercaseName() == mcpSessionHeader)
      .map(_.value())

    // TODO: Introduce user identification from auth header or other means
    
    sessionIdOpt match {
      case Some(mcpSessionId) =>
        logger.debug(s"Received request with $mcpSessionHeader: $mcpSessionId")
        // ask returns a Future; return that directly
        ActorLookup.getMcpSessionMapActor("changeThisToUserId").ask[Either[SessionMapError, String]](
          replyTo => McpSessionMapActor.SessionLookup(mcpSessionId, replyTo)
        ).map {
          case Right(actorId) =>
            ActiveSessionResult(
              mcpActorId = actorId,
              sessionIdOpt = Some(mcpSessionId)
            )
          case Left(error) =>
            logger.error(s"Session lookup error for $mcpSessionId: ${error}, generating new actor Id")
            val actorId = generateActorId("changeThisToUserId")

            ActiveSessionResult(
              mcpActorId = actorId,
              sessionIdOpt = Some(mcpSessionId)
            )
        }

      case None =>
        logger.debug(s"$mcpSessionHeader header NOT found in request. Generating new actor ID.")
        val actorId = "changeThisToUserId-" + Random.generateUuid()

        Future.successful(
          ActiveSessionResult(
            mcpActorId = "changeThisToUserId-" + Random.generateUuid(),
            sessionIdOpt = None
          )
        )
    }
  }

  /**
   * Update the session-to-actor mapping if a new mcp-session-id is observed in a response.
   */
  def updateSessionMapping(
    sessionId: String, 
    actorId: String
  ): Unit = {
    if (!sessionId.isEmpty && !actorId.isEmpty) {
      ActorLookup.getMcpSessionMapActor("changeThisToUserId") !
        McpSessionMapActor.SessionCreate(sessionId, actorId)

      logger.debug(s"Updated session mapping: $sessionId -> $actorId")
    }
  }

  private def generateActorId(userId: String): String = {
    userId + "-" + Random.generateUuid()
  }
}

object SessionMap {
  /**
   * Create a HeaderBasedRouter with handler factory and optional fallback to default handler.
   *
   * @param registry Handler registry
   * @param handlerFactory Function to create a handler given an actor-id
   */
  def apply()
    (implicit
      ec: ExecutionContext,
      system: ActorSystem[?],
      sharding: ClusterSharding,
      timeout: Timeout): SessionMap = new SessionMap()
}
