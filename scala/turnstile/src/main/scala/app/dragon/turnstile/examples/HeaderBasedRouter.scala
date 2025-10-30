package app.dragon.turnstile.examples

import app.dragon.turnstile.utils.Random

import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.server.reactive.HttpHandler

import scala.concurrent.{ExecutionContext, Future}

/**
 * Simplified router: routes HTTP requests to WebFlux handlers based on a header value.
 *
 * Looks up the handler ID directly from the specified header value.
 * If not found, optionally falls back to the default handler.
 */
class HeaderBasedRouter(
  //handlerFactory: String => HttpHandler, // New: factory to create handler by actor-id
  serverVersion: String = "1.0", // Optional: can be parameterized
  toolNamespace: String = "default" // Optional: can be parameterized
) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[HeaderBasedRouter])

  // Thread-safe map for mcp-session-id -> actor-id
  private val sessionToActor = new ConcurrentHashMap[String, String]()

  case class RouteLookupResult(
    mcpActorId: String,
    sessionIdOpt: Option[String]
  )

  /**
   * Route a Pekko HTTP request to the appropriate WebFlux handler.
   *
   * @param pekkoRequest The incoming Pekko HTTP request
   * @return Future[Either[HttpResponse, (String, HttpHandler)]]
   */
  def route(pekkoRequest: HttpRequest)(implicit ec: ExecutionContext): Future[RouteLookupResult] = {
    Future {
      // Extract mcp-session-id header value
      val sessionHeader = "mcp-session-id"
      val sessionIdOpt = pekkoRequest.headers
        .find(_.lowercaseName() == sessionHeader)
        .map(_.value())

      // if sessionId is present, look up actorId from mapping otherwise generate a fresh one
      //  and create a mapping
      sessionIdOpt match {
        case Some(sid) if sessionToActor.containsKey(sid) =>
          logger.debug(s"Received request with $sessionHeader: $sid")
          RouteLookupResult(
            mcpActorId = sessionToActor.get(sid),
            sessionIdOpt = Some(sid)
          )
        case Some(sid) =>
          logger.debug(s"No existing actor mapping for $sessionHeader: $sid, generating new actor ID")
          RouteLookupResult(
            mcpActorId = "changeThisToUserId-"+Random.generateUuid(),
            sessionIdOpt = Some(sid)
          )
        case None =>
          logger.debug(s"No $sessionHeader header found in request")
          //Random.generateRandBase64String(10)
          RouteLookupResult(
            mcpActorId = "changeThisToUserId-"+Random.generateUuid(),
            sessionIdOpt = None
          )
      }
    }
  }

  /**
   * Update the session-to-actor mapping if a new mcp-session-id is observed in a response.
   */
  def updateSessionMapping(sessionId: String, actorId: String): Unit = {
    if (sessionId != null && actorId != null) {
      sessionToActor.put(sessionId, actorId)
      logger.debug(s"Updated session mapping: $sessionId -> $actorId")
    }
  }

  private def createErrorResponse(status: StatusCodes.ClientError, message: String): HttpResponse = {
    HttpResponse(
      status = status,
      entity = HttpEntity(
        ContentTypes.`application/json`,
        s"""{"error": "$message"}"""
      )
    )
  }
}

object HeaderBasedRouter {
  /**
   * Create a HeaderBasedRouter with handler factory and optional fallback to default handler.
   *
   * @param registry Handler registry
   * @param handlerFactory Function to create a handler given an actor-id
   */
  def apply(
  ): HeaderBasedRouter =
    new HeaderBasedRouter()
}
