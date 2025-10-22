package app.dragon.turnstile.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.{McpSchema, McpStatelessServerTransport}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Custom Pekko HTTP transport implementation for MCP Stateless Server.
 * Implements the official SDK's McpStatelessServerTransport interface.
 *
 * This transport:
 * - Handles HTTP POST requests with JSON-RPC messages
 * - Integrates with Pekko HTTP for efficient request handling
 * - Delegates MCP protocol handling to the SDK's McpStatelessServerHandler
 * - Supports graceful shutdown
 *
 * @param host The host address to bind to
 * @param port The port number to bind to
 * @param endpoint The endpoint path (e.g., "/mcp")
 * @param system The Pekko ActorSystem for HTTP server
 */
class PekkoHttpStatelessServerTransport(
  host: String,
  port: Int,
  endpoint: String
)(implicit system: ActorSystem[?]) extends McpStatelessServerTransport {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PekkoHttpStatelessServerTransport])
  private implicit val ec: ExecutionContext = system.executionContext

  private val jsonMapper: McpJsonMapper = McpJsonMapper.getDefault
  private val bindingRef: AtomicReference[Option[Http.ServerBinding]] = new AtomicReference(None)
  private var mcpHandler: McpStatelessServerHandler = _

  @volatile private var isClosing = false

  override def setMcpHandler(handler: McpStatelessServerHandler): Unit = {
    this.mcpHandler = handler
  }

  /**
   * Start the HTTP server and bind to the configured host/port
   */
  def start(): Unit = {
    if (mcpHandler == null) {
      throw new IllegalStateException("MCP handler must be set before starting the transport")
    }

    val route = createMcpRoute()
    val binding = Http().newServerAt(host, port).bind(route)

    binding.onComplete {
      case Success(b) =>
        bindingRef.set(Some(b))
        logger.info(s"Pekko HTTP MCP Transport bound to http://$host:$port$endpoint")
      case Failure(ex) =>
        logger.error(s"Failed to bind Pekko HTTP MCP Transport to $host:$port", ex)
    }
  }

  /**
   * Create the HTTP route for the MCP endpoint
   */
  private def createMcpRoute(): Route = {
    concat(
      path(endpoint.stripPrefix("/")) {
        post {
          entity(as[String]) { requestBody =>
            complete {
              if (isClosing) {
                HttpResponse(
                  status = StatusCodes.ServiceUnavailable,
                  entity = HttpEntity(ContentTypes.`application/json`, """{"error":"Server is shutting down"}""")
                )
              } else {
                handleMcpRequest(requestBody)
              }
            }
          }
        } ~
        get {
          complete {
            HttpResponse(
              status = StatusCodes.MethodNotAllowed,
              entity = HttpEntity(ContentTypes.`application/json`, """{"error":"POST method required"}""")
            )
          }
        }
      },
      path("messages") {
        post {
          entity(as[String]) { requestBody =>
            // For now, echo the request body as a JSON response
            complete(HttpEntity(ContentTypes.`application/json`, s"""{"response": $requestBody}"""))
          }
        }
      }
    )
  }

  /**
   * Handle an incoming MCP request by delegating to the SDK's handler
   */
  private def handleMcpRequest(requestBody: String): Future[HttpResponse] = {
    try {
      // Deserialize the JSON-RPC message
      val message = McpSchema.deserializeJsonRpcMessage(jsonMapper, requestBody)

      message match {
        case request: McpSchema.JSONRPCRequest =>
          // Handle request using the MCP handler
          val transportContext = McpTransportContext.EMPTY
          val responseMono = mcpHandler.handleRequest(transportContext, request)

          // Convert Reactor Mono to Scala Future
          monoToFuture(responseMono).map { response =>
            val responseJson = jsonMapper.writeValueAsString(response)
            HttpResponse(
              status = StatusCodes.OK,
              entity = HttpEntity(ContentTypes.`application/json`, responseJson)
            )
          }.recover {
            case ex: Exception =>
              logger.error("Failed to handle MCP request", ex)
              HttpResponse(
                status = StatusCodes.InternalServerError,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  s"""{"error":"Failed to handle request: ${ex.getMessage}"}"""
                )
              )
          }

        case notification: McpSchema.JSONRPCNotification =>
          // Handle notification using the MCP handler
          val transportContext = McpTransportContext.EMPTY
          val notificationMono = mcpHandler.handleNotification(transportContext, notification)

          monoToFuture(notificationMono).map { _ =>
            HttpResponse(status = StatusCodes.Accepted)
          }.recover {
            case ex: Exception =>
              logger.error("Failed to handle MCP notification", ex)
              HttpResponse(
                status = StatusCodes.InternalServerError,
                entity = HttpEntity(
                  ContentTypes.`application/json`,
                  s"""{"error":"Failed to handle notification: ${ex.getMessage}"}"""
                )
              )
          }

        case _ =>
          Future.successful(
            HttpResponse(
              status = StatusCodes.BadRequest,
              entity = HttpEntity(
                ContentTypes.`application/json`,
                """{"error":"Invalid message type"}"""
              )
            )
          )
      }
    } catch {
      case ex: Exception =>
        logger.error("Failed to deserialize MCP message", ex)
        Future.successful(
          HttpResponse(
            status = StatusCodes.BadRequest,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              s"""{"error":"Invalid message format: ${ex.getMessage}"}"""
            )
          )
        )
    }
  }

  /**
   * Convert a Reactor Mono to a Scala Future
   */
  private def monoToFuture[T](mono: Mono[T]): Future[T] = {
    val promise = scala.concurrent.Promise[T]()
    mono.subscribe(
      value => promise.success(value),
      error => promise.failure(error),
      () => if (!promise.isCompleted) promise.failure(new RuntimeException("Mono completed without value"))
    )
    promise.future
  }

  override def closeGracefully(): Mono[Void] = {
    Mono.fromRunnable(() => {
      logger.info("Stopping Pekko HTTP MCP Transport gracefully")
      isClosing = true

      bindingRef.get() match {
        case Some(binding) =>
          binding.unbind().onComplete {
            case Success(_) =>
              bindingRef.set(None)
              logger.info("Pekko HTTP MCP Transport stopped")
            case Failure(ex) =>
              logger.error("Error while stopping Pekko HTTP MCP Transport", ex)
          }
        case None =>
          logger.warn("Pekko HTTP MCP Transport was not running")
      }
    })
  }

  override def close(): Unit = {
    closeGracefully().block()
  }
}
