package app.dragon.turnstile.examples

import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.server.reactive.HttpHandler

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Routes HTTP requests to different WebFlux handlers based on header values.
 *
 * This router examines request headers (like mcp-session-id) and selects the
 * appropriate handler from a registry. It supports multiple routing strategies:
 * - Direct mapping: header value → handler ID
 * - Pattern matching: header value matches pattern → handler ID
 * - Custom routing logic: user-defined function
 * - Fallback: default handler if no match
 *
 * Architecture:
 * {{{
 * Request → HeaderBasedRouter → WebFluxHandlerRegistry → HttpHandler
 * }}}
 *
 * Example usage:
 * {{{
 * val registry = new WebFluxHandlerRegistry()
 * registry.register("handler-a", handlerA)
 * registry.register("handler-b", handlerB)
 * registry.register("default", defaultHandler)
 *
 * val router = HeaderBasedRouter(
 *   registry = registry,
 *   routingHeader = "mcp-session-id",
 *   routingStrategy = HeaderBasedRouter.PrefixStrategy("session-", stripPrefix = true)
 * )
 *
 * // Request with "mcp-session-id: session-handler-a" → routes to handler-a
 * // Request with "mcp-session-id: session-handler-b" → routes to handler-b
 * // Request without header → routes to default
 * }}}
 */
class HeaderBasedRouter(
  registry: WebFluxHandlerRegistry,
  routingHeader: String,
  routingStrategy: HeaderBasedRouter.RoutingStrategy,
  fallbackToDefault: Boolean = true
) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[HeaderBasedRouter])

  /**
   * Route a Pekko HTTP request to the appropriate WebFlux handler.
   *
   * @param pekkoRequest The incoming Pekko HTTP request
   * @return Future[HttpResponse] or error response if no handler found
   */
  def route(pekkoRequest: HttpRequest)(implicit ec: ExecutionContext): Future[Either[HttpResponse, HttpHandler]] = {
    Future {
      // Extract header value
      val headerValue = pekkoRequest.headers
        .find(_.lowercaseName() == routingHeader.toLowerCase)
        .map(_.value())

      logger.debug(s"Routing request with $routingHeader: $headerValue")

      // Apply routing strategy
      val handlerId = headerValue.flatMap(routingStrategy.route)

      // Get handler from registry
      val handler = handlerId.flatMap(id => {
        logger.debug(s"Attempting to get handler: $id")
        registry.get(id)
      })

      handler match {
        case Some(h) =>
          logger.debug(s"Routed to handler: ${handlerId.get}")
          Right(h)
        case None if fallbackToDefault =>
          registry.getDefault() match {
            case Some(defaultHandler) =>
              logger.debug(s"Using default handler (header value: $headerValue)")
              Right(defaultHandler)
            case None =>
              logger.warn(s"No handler found for $routingHeader=$headerValue and no default handler configured")
              Left(createErrorResponse(StatusCodes.NotFound, s"No handler configured for session"))
          }
        case None =>
          logger.warn(s"No handler found for $routingHeader=$headerValue")
          Left(createErrorResponse(StatusCodes.NotFound, s"Handler not found for: $headerValue"))
      }
    }
  }

  /**
   * Get handler ID that would be selected for a given header value.
   *
   * @param headerValue The header value to test
   * @return Some(handlerId) if a handler would be selected, None otherwise
   */
  def getHandlerIdForValue(headerValue: String): Option[String] = {
    routingStrategy.route(headerValue)
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
  private val logger: Logger = LoggerFactory.getLogger(HeaderBasedRouter.getClass)

  /**
   * Routing strategy determines how header values map to handler IDs.
   */
  trait RoutingStrategy {
    /**
     * Route a header value to a handler ID.
     *
     * @param headerValue The value from the routing header
     * @return Some(handlerId) if a handler should be used, None otherwise
     */
    def route(headerValue: String): Option[String]
  }

  /**
   * Direct mapping strategy: header value is the handler ID.
   *
   * Example: "handler-a" → handler ID "handler-a"
   */
  case object DirectStrategy extends RoutingStrategy {
    override def route(headerValue: String): Option[String] = {
      Some(headerValue)
    }
  }

  /**
   * Prefix strategy: strip a prefix from header value to get handler ID.
   *
   * Example: with prefix "session-", "session-abc123" → handler ID "abc123"
   *
   * @param prefix The prefix to look for
   * @param stripPrefix If true, remove prefix to get handler ID; if false, use full value
   */
  case class PrefixStrategy(prefix: String, stripPrefix: Boolean = true) extends RoutingStrategy {
    override def route(headerValue: String): Option[String] = {
      if (headerValue.startsWith(prefix)) {
        if (stripPrefix) {
          Some(headerValue.stripPrefix(prefix))
        } else {
          Some(headerValue)
        }
      } else {
        None
      }
    }
  }

  /**
   * Regex strategy: match header value against regex and extract handler ID.
   *
   * Example: with pattern "tenant-(\w+)-.*", "tenant-abc-123" → handler ID "abc"
   *
   * @param pattern Regex pattern with optional capture group for handler ID
   * @param groupIndex Which capture group contains the handler ID (default: 1)
   */
  case class RegexStrategy(pattern: String, groupIndex: Int = 1) extends RoutingStrategy {
    private val regex = pattern.r

    override def route(headerValue: String): Option[String] = {
      regex.findFirstMatchIn(headerValue).flatMap { m =>
        Try(m.group(groupIndex)).toOption
      }
    }
  }

  /**
   * Hash strategy: hash the header value and map to one of N handlers.
   *
   * Useful for load balancing across a fixed set of handlers.
   *
   * Example: with handlerIds = Seq("h1", "h2", "h3"),
   *          any header value will consistently hash to one of these three.
   *
   * @param handlerIds List of handler IDs to distribute across
   */
  case class HashStrategy(handlerIds: Seq[String]) extends RoutingStrategy {
    require(handlerIds.nonEmpty, "Must provide at least one handler ID")

    override def route(headerValue: String): Option[String] = {
      val hash = Math.abs(headerValue.hashCode)
      val index = hash % handlerIds.length
      Some(handlerIds(index))
    }
  }

  /**
   * Custom strategy: use a user-defined function for routing.
   *
   * @param routingFunction Function that maps header value to handler ID
   */
  case class CustomStrategy(routingFunction: String => Option[String]) extends RoutingStrategy {
    override def route(headerValue: String): Option[String] = {
      routingFunction(headerValue)
    }
  }

  /**
   * Session-based strategy: extracts session ID and routes to handler managing that session.
   *
   * This is specifically designed for MCP's mcp-session-id header pattern.
   * Sessions are typically UUIDs or similar identifiers.
   *
   * @param sessionToHandler Map from session ID to handler ID
   * @param defaultHandler Handler ID to use if session not found
   */
  case class SessionAffinityStrategy(
    sessionToHandler: scala.collection.concurrent.Map[String, String],
    defaultHandler: Option[String] = None
  ) extends RoutingStrategy {
    override def route(headerValue: String): Option[String] = {
      sessionToHandler.get(headerValue).orElse(defaultHandler)
    }

    /**
     * Register a session affinity (session → handler mapping).
     */
    def registerSession(sessionId: String, handlerId: String): Unit = {
      sessionToHandler.put(sessionId, handlerId)
      logger.debug(s"Registered session affinity: $sessionId → $handlerId")
    }

    /**
     * Unregister a session affinity.
     */
    def unregisterSession(sessionId: String): Unit = {
      sessionToHandler.remove(sessionId)
      logger.debug(s"Unregistered session: $sessionId")
    }
  }

  /**
   * Create a HeaderBasedRouter with direct strategy.
   */
  def direct(
    registry: WebFluxHandlerRegistry,
    routingHeader: String,
    fallbackToDefault: Boolean = true
  ): HeaderBasedRouter = {
    new HeaderBasedRouter(registry, routingHeader, DirectStrategy, fallbackToDefault)
  }

  /**
   * Create a HeaderBasedRouter with session affinity strategy.
   *
   * This is ideal for MCP servers where you want to maintain sticky sessions.
   */
  def withSessionAffinity(
    registry: WebFluxHandlerRegistry,
    routingHeader: String = "mcp-session-id",
    defaultHandler: Option[String] = Some("default")
  ): (HeaderBasedRouter, SessionAffinityStrategy) = {
    val sessionMap = scala.collection.concurrent.TrieMap[String, String]()
    val strategy = SessionAffinityStrategy(sessionMap, defaultHandler)
    val router = new HeaderBasedRouter(registry, routingHeader, strategy, fallbackToDefault = true)
    (router, strategy)
  }

  /**
   * Create a HeaderBasedRouter with hash-based load balancing.
   */
  def withLoadBalancing(
    registry: WebFluxHandlerRegistry,
    routingHeader: String,
    handlerIds: Seq[String]
  ): HeaderBasedRouter = {
    new HeaderBasedRouter(registry, routingHeader, HashStrategy(handlerIds), fallbackToDefault = false)
  }

  /**
   * Create a HeaderBasedRouter with custom routing logic.
   */
  def custom(
    registry: WebFluxHandlerRegistry,
    routingHeader: String,
    routingFunction: String => Option[String],
    fallbackToDefault: Boolean = true
  ): HeaderBasedRouter = {
    new HeaderBasedRouter(
      registry,
      routingHeader,
      CustomStrategy(routingFunction),
      fallbackToDefault
    )
  }
}
