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
import app.dragon.turnstile.mcp_server.{McpServerActor, McpServerActorId}
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
class McpSessionMap()(
  implicit ec: ExecutionContext,
  system: ActorSystem[?],
  sharding: ClusterSharding, timeout: Timeout
) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[McpSessionMap])

  case class ActiveSessionResult(mcpServerActorId: McpServerActorId, sessionIdOpt: Option[String])

  final val mcpSessionHeader = "mcp-session-id"
  final val authHeader = "authorization"
  
  /**
   * Route a Pekko HTTP request to the appropriate WebFlux handler.
   *
   * @param pekkoRequest The incoming Pekko HTTP request
   * @return Future[Either[HttpResponse, (String, HttpHandler)]]
   */
  def lookup(
    userId: String,
    pekkoRequest: HttpRequest
  ): Future[ActiveSessionResult] = {
    // Extract mcp-session-id header value
    
    val sessionIdOpt = pekkoRequest.headers
      .find(_.lowercaseName() == mcpSessionHeader)
      .map(_.value())

    sessionIdOpt match {
      case Some(mcpSessionId) =>
        logger.debug(s"Received request with $mcpSessionHeader: $mcpSessionId")
        // ask returns a Future; return that directly
        ActorLookup.getMcpSessionMapActor(userId).ask[Either[SessionMapError, McpServerActorId]](
          replyTo => McpSessionMapActor.SessionLookup(mcpSessionId, replyTo)
        ).map {
          case Right(mcpServerActor) =>
            ActiveSessionResult(mcpServerActor, Some(mcpSessionId))
          case Left(error) =>
            logger.error(s"Session lookup error for $mcpSessionId: ${error}, generating new actor Id")
            val mcpServerActorId = generateMcpServerActorId(userId)

            ActiveSessionResult(mcpServerActorId, Some(mcpSessionId))
        }

      case None =>
        logger.debug(s"$mcpSessionHeader header NOT found in request. Generating new actor ID.")
        val mcpServerActorId = generateMcpServerActorId(userId)

        Future.successful(ActiveSessionResult(mcpServerActorId, None))
    }
  }

  /**
   * Update the session-to-actor mapping if a new mcp-session-id is observed in a response.
   */
  def updateSessionMapping(
    sessionId: String,
    mcpServerActorId: McpServerActorId,
  ): Unit = {
    if (!sessionId.isEmpty) {
      ActorLookup.getMcpSessionMapActor(mcpServerActorId.userId) !
        McpSessionMapActor.SessionCreate(sessionId, mcpServerActorId)

      logger.debug(s"Updated session mapping: $sessionId -> $mcpServerActorId")
    }
  }

  private def generateMcpServerActorId(userId: String): McpServerActorId = {
    //McpServerActorId(userId, Random.generateUuid())
    // TODO: For now, we will use a singleton MCP server actor per user
    //        This may change in the future to support multiple sessions per user
    McpServerActorId(userId)  
  }
}

object McpSessionMap {
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
      timeout: Timeout): McpSessionMap = new McpSessionMap()
}
