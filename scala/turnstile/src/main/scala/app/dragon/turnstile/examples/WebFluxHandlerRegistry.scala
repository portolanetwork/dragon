package app.dragon.turnstile.examples

import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.server.reactive.HttpHandler

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Registry for managing multiple WebFlux HttpHandlers.
 *
 * This registry allows dynamic registration and deregistration of WebFlux handlers,
 * enabling routing to different MCP server instances based on request characteristics.
 *
 * Use cases:
 * - Multi-tenancy: Route different sessions to different server instances
 * - Load balancing: Distribute sessions across multiple handler pools
 * - Feature flagging: Route to experimental vs stable implementations
 * - Session affinity: Maintain sticky sessions to specific handlers
 *
 * Example:
 * {{{
 * val registry = new WebFluxHandlerRegistry()
 *
 * // Register handlers
 * registry.register("tenant-a", handlerA)
 * registry.register("tenant-b", handlerB)
 *
 * // Get handler by ID
 * registry.get("tenant-a").foreach { handler =>
 *   // Use handler
 * }
 *
 * // Unregister when done
 * registry.unregister("tenant-a")
 * }}}
 */
class WebFluxHandlerRegistry {
  private val logger: Logger = LoggerFactory.getLogger(classOf[WebFluxHandlerRegistry])
  private val handlers = new ConcurrentHashMap[String, HttpHandler]()
  private val metadata = new ConcurrentHashMap[String, HandlerMetadata]()

  /**
   * Register a WebFlux HttpHandler with a unique identifier.
   *
   * @param id Unique identifier for this handler
   * @param handler The HttpHandler to register
   * @param meta Optional metadata about this handler
   * @return true if registered successfully, false if ID already exists
   */
  def register(id: String, handler: HttpHandler, meta: Option[HandlerMetadata] = None): Boolean = {
    if (handlers.containsKey(id)) {
      logger.warn(s"Handler with ID '$id' already exists")
      false
    } else {
      handlers.put(id, handler)
      metadata.put(id, meta.getOrElse(HandlerMetadata(id, Map.empty)))
      logger.info(s"Registered handler: $id")
      true
    }
  }

  /**
   * Unregister a handler by ID.
   *
   * @param id The handler ID to remove
   * @return true if unregistered successfully, false if not found
   */
  def unregister(id: String): Boolean = {
    val removed = handlers.remove(id) != null
    metadata.remove(id)
    if (removed) {
      logger.info(s"Unregistered handler: $id")
    } else {
      logger.warn(s"Handler not found for unregistration: $id")
    }
    removed
  }

  /**
   * Get a handler by ID.
   *
   * @param id The handler ID
   * @return Some(handler) if found, None otherwise
   */
  def get(id: String): Option[HttpHandler] = {
    Option(handlers.get(id))
  }

  /**
   * Get handler metadata by ID.
   *
   * @param id The handler ID
   * @return Some(metadata) if found, None otherwise
   */
  def getMetadata(id: String): Option[HandlerMetadata] = {
    Option(metadata.get(id))
  }

  /**
   * Get all registered handler IDs.
   *
   * @return Set of all handler IDs
   */
  def getAllIds(): Set[String] = {
    handlers.keySet().asScala.toSet
  }

  /**
   * Get the number of registered handlers.
   *
   * @return Count of registered handlers
   */
  def size(): Int = handlers.size()

  /**
   * Check if a handler is registered.
   *
   * @param id The handler ID to check
   * @return true if registered, false otherwise
   */
  def contains(id: String): Boolean = {
    handlers.containsKey(id)
  }

  /**
   * Clear all handlers from the registry.
   */
  def clear(): Unit = {
    val count = handlers.size()
    handlers.clear()
    metadata.clear()
    logger.info(s"Cleared $count handlers from registry")
  }

  /**
   * Get a default handler if one is configured.
   *
   * A default handler can be set by registering with ID "default".
   *
   * @return Some(handler) if a default handler exists, None otherwise
   */
  def getDefault(): Option[HttpHandler] = {
    get("default")
  }
}

/**
 * Metadata associated with a registered handler.
 *
 * @param id Handler identifier
 * @param tags Arbitrary key-value tags for filtering/selection
 * @param description Optional human-readable description
 * @param weight Optional weight for load balancing (higher = more requests)
 */
case class HandlerMetadata(
  id: String,
  tags: Map[String, String],
  description: Option[String] = None,
  weight: Int = 1
)

object WebFluxHandlerRegistry {
  /**
   * Create a new registry instance.
   */
  def apply(): WebFluxHandlerRegistry = new WebFluxHandlerRegistry()
}
