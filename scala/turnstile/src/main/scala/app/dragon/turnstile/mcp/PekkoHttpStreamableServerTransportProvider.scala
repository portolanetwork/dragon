package app.dragon.turnstile.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.{McpJsonMapper, TypeRef}
import io.modelcontextprotocol.server.McpTransportContextExtractor
import io.modelcontextprotocol.spec.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Source, SourceQueueWithComplete}
import org.apache.pekko.stream.{OverflowStrategy, QueueOfferResult}
import org.apache.pekko.util.ByteString
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono

import java.time.Duration
import java.util.concurrent.{ConcurrentHashMap, locks}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Pekko HTTP implementation of MCP streamable transport using Server-Sent Events (SSE).
 *
 * This is the Pekko HTTP equivalent of HttpServletStreamableServerTransportProvider,
 * providing HTTP streaming for the Model Context Protocol using:
 * - Server-Sent Events (SSE) for server-to-client messages (GET requests)
 * - HTTP POST for client-to-server messages
 * - Session-based communication with mcp-session-id headers
 * - Message replay support using Last-Event-ID
 *
 * Architecture:
 * - GET /mcp: Establish SSE connection for receiving server messages
 * - POST /mcp: Send client messages (initialize, requests, responses, notifications)
 * - DELETE /mcp: Delete session (optional)
 *
 * The transport manages multiple concurrent sessions, each with its own SSE stream.
 *
 * @param jsonMapper The JSON mapper for MCP message serialization
 * @param mcpEndpoint The endpoint path (default: "/mcp")
 * @param disallowDelete Whether to disallow DELETE requests
 * @param contextExtractor Extractor for transport context from HTTP requests
 * @param system The Pekko ActorSystem
 */
class PekkoHttpStreamableServerTransportProvider(
  jsonMapper: McpJsonMapper,
  mcpEndpoint: String,
  disallowDelete: Boolean,
  contextExtractor: McpTransportContextExtractor[HttpRequest],
  host: String,
  port: Int
)(implicit system: ActorSystem[?]) extends McpStreamableServerTransportProvider {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PekkoHttpStreamableServerTransportProvider])
  private implicit val ec: ExecutionContext = system.executionContext

  // Event types
  private val MESSAGE_EVENT_TYPE = "message"
  private val ENDPOINT_EVENT_TYPE = "endpoint"

  // Session factory set by MCP SDK
  @volatile private var sessionFactory: McpStreamableServerSession.Factory = _

  // Active sessions
  private val sessions = new ConcurrentHashMap[String, McpStreamableServerSession]()

  // Listening transports for each session (used to send responses)
  private val listeningTransports = new ConcurrentHashMap[String, PekkoHttpStreamableMcpSessionTransport]()

  // Server binding
  @volatile private var binding: Option[Http.ServerBinding] = None

  // Shutdown flag
  @volatile private var isClosing = false

  override def protocolVersions(): java.util.List[String] = {
    List(
      ProtocolVersions.MCP_2024_11_05,
      ProtocolVersions.MCP_2025_03_26,
      ProtocolVersions.MCP_2025_06_18
    ).asJava
  }

  override def setSessionFactory(factory: McpStreamableServerSession.Factory): Unit = {
    this.sessionFactory = factory
  }

  override def notifyClients(method: String, params: Object): Mono[Void] = {
    if (sessions.isEmpty) {
      logger.debug("No active sessions to broadcast to")
      return Mono.empty()
    }

    logger.debug(s"Broadcasting to ${sessions.size()} sessions")

    Mono.fromRunnable(() => {
      sessions.values().asScala.foreach { session =>
        try {
          session.sendNotification(method, params).block()
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to send notification to session ${session.getId}: ${ex.getMessage}")
        }
      }
    })
  }

  override def closeGracefully(): Mono[Void] = {
    Mono.fromRunnable(() => {
      isClosing = true
      logger.debug(s"Closing gracefully with ${sessions.size()} active sessions")

      // Close all sessions
      sessions.values().asScala.foreach { session =>
        try {
          session.closeGracefully().block()
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to close session ${session.getId}: ${ex.getMessage}")
        }
      }

      sessions.clear()

      // Unbind HTTP server
      binding.foreach { b =>
        b.unbind().onComplete {
          case Success(_) =>
            logger.info("HTTP streaming server unbound successfully")
          case Failure(ex) =>
            logger.error("Error unbinding HTTP streaming server", ex)
        }
      }

      logger.debug("Graceful shutdown completed")
    })
  }

  /**
   * Start the HTTP server
   */
  def start(): Future[Http.ServerBinding] = {
    val route = createRoute()
    val bindingFuture = Http()(system).newServerAt(host, port).bind(route)

    bindingFuture.onComplete {
      case Success(b) =>
        binding = Some(b)
        logger.info(s"HTTP Streaming MCP Server bound to http://$host:$port$mcpEndpoint")
      case Failure(ex) =>
        logger.error(s"Failed to bind HTTP Streaming MCP Server to $host:$port", ex)
    }

    bindingFuture
  }

  /**
   * Create the Pekko HTTP route handling GET, POST, DELETE
   */
  private def createRoute(): Route = {
    path(mcpEndpoint.stripPrefix("/")) {
      get {
        handleGet()
      } ~
      post {
        handlePost()
      } ~
      delete {
        handleDelete()
      }
    }
  }

  /**
   * Handle GET requests for SSE connections
   */
  private def handleGet(): Route = {
    if (isClosing) {
      complete(StatusCodes.ServiceUnavailable -> "Server is shutting down")
    } else {
      optionalHeaderValueByName("Accept") { acceptOpt =>
        optionalHeaderValueByName(HttpHeaders.MCP_SESSION_ID) { sessionIdOpt =>
          optionalHeaderValueByName("Last-Event-ID") { lastEventIdOpt =>
            (acceptOpt, sessionIdOpt) match {
              case (Some(accept), Some(sessionId)) if accept.contains("text/event-stream") =>
                sessions.get(sessionId) match {
                  case null =>
                    complete(StatusCodes.NotFound -> "Session not found")
                  case session =>
                    handleSseStream(session, sessionId, lastEventIdOpt)
                }
              case (None, _) | (Some(_), _) =>
                complete(StatusCodes.BadRequest -> "text/event-stream required in Accept header")
              case (_, None) =>
                complete(StatusCodes.BadRequest -> "Session ID required in mcp-session-id header")
            }
          }
        }
      }
    }
  }

  /**
   * Handle SSE stream for a session
   */
  private def handleSseStream(session: McpStreamableServerSession, sessionId: String, lastEventIdOpt: Option[String]): Route = {
    extractRequest { httpRequest =>
      val transportContext = contextExtractor.extract(httpRequest)

      // Create SSE stream transport
      val (queue, source) = Source.queue[ServerSentEvent](bufferSize = 100, OverflowStrategy.backpressure)
        .toMat(BroadcastHub.sink)(Keep.both)
        .run()

      val sessionTransport = new PekkoHttpStreamableMcpSessionTransport(sessionId, queue, jsonMapper)

      // Handle replay if Last-Event-ID is present
      lastEventIdOpt.foreach { lastId =>
        try {
          session.replay(lastId)
            .contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext))
            .toIterable
            .asScala
            .foreach { message =>
              sessionTransport.sendMessage(message)
                .contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext))
                .block()
            }
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to replay messages: ${ex.getMessage}")
        }
      }

      if (lastEventIdOpt.isEmpty) {
        // Establish new listening stream and store transport for later use
        session.listeningStream(sessionTransport)
        listeningTransports.put(sessionId, sessionTransport)
      }

      val eventStream = source.map(event => ByteString(event.toString))
      complete(
        HttpEntity.Chunked.fromData(
          MediaTypes.`text/event-stream`.toContentType,
          eventStream
        )
      )
    }
  }

  /**
   * Handle POST requests for client messages
   */
  private def handlePost(): Route = {
    if (isClosing) {
      complete(StatusCodes.ServiceUnavailable -> "Server is shutting down")
    } else {
      extractRequest { httpRequest =>
        optionalHeaderValueByName("Accept") { acceptOpt =>
          entity(as[String]) { body =>
            acceptOpt match {
              case Some(accept) if accept.contains("text/event-stream") =>
                handlePostWithBody(httpRequest, body, accept.contains("application/json"))
              case _ =>
                complete(StatusCodes.BadRequest -> "text/event-stream required in Accept header")
            }
          }
        }
      }
    }
  }

  private def handlePostWithBody(httpRequest: HttpRequest, body: String, acceptsJson: Boolean): Route = {
    try {
      val message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body)
      val transportContext = contextExtractor.extract(httpRequest)

      message match {
        // Handle initialize request
        case request: McpSchema.JSONRPCRequest if request.method() == McpSchema.METHOD_INITIALIZE =>
          if (!acceptsJson) {
            complete(StatusCodes.BadRequest -> "application/json required in Accept header for initialize")
          } else {
            val initRequest = jsonMapper.convertValue(request.params(), new TypeRef[McpSchema.InitializeRequest]() {})
            val init = sessionFactory.startSession(initRequest)
            sessions.put(init.session().getId, init.session())

            try {
              val initResult = init.initResult().block()
              val jsonResponse = jsonMapper.writeValueAsString(
                new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), initResult, null)
              )

              complete(
                HttpResponse(
                  StatusCodes.OK,
                  headers = List(RawHeader(HttpHeaders.MCP_SESSION_ID, init.session().getId)),
                  entity = HttpEntity(ContentTypes.`application/json`, jsonResponse)
                )
              )
            } catch {
              case ex: Exception =>
                logger.error(s"Failed to initialize session: ${ex.getMessage}")
                complete(StatusCodes.InternalServerError -> s"Failed to initialize: ${ex.getMessage}")
            }
          }

        // Handle other requests (require session ID)
        case _ =>
          httpRequest.headers.find(_.name() == HttpHeaders.MCP_SESSION_ID).map(_.value()) match {
            case Some(sessionId) =>
              sessions.get(sessionId) match {
                case null =>
                  complete(StatusCodes.NotFound -> s"Session not found: $sessionId")
                case session =>
                  handleSessionMessage(session, message, transportContext, sessionId)
              }
            case None =>
              complete(StatusCodes.BadRequest -> "Session ID required in mcp-session-id header")
          }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to deserialize message: ${ex.getMessage}")
        complete(StatusCodes.BadRequest -> s"Invalid message format: ${ex.getMessage}")
    }
  }

  private def handleSessionMessage(
    session: McpStreamableServerSession,
    message: McpSchema.JSONRPCMessage,
    transportContext: McpTransportContext,
    sessionId: String
  ): Route = {
    message match {
      case response: McpSchema.JSONRPCResponse =>
        session.accept(response).contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext)).block()
        complete(StatusCodes.Accepted)

      case notification: McpSchema.JSONRPCNotification =>
        session.accept(notification).contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext)).block()
        complete(StatusCodes.Accepted)

      case request: McpSchema.JSONRPCRequest =>
        // Use the listening transport if available, otherwise create per-request stream
        val transport = listeningTransports.get(sessionId)
        if (transport != null) {
          // Send response via the listening stream
          try {
            session.responseStream(request, transport)
              .contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext))
              .block()
            complete(StatusCodes.Accepted)
          } catch {
            case ex: Exception =>
              logger.error(s"Failed to handle request: ${ex.getMessage}", ex)
              complete(StatusCodes.InternalServerError -> ex.getMessage)
          }
        } else {
          // No listening stream - create per-request SSE stream
          val (queue, source) = Source.queue[ServerSentEvent](bufferSize = 100, OverflowStrategy.backpressure)
            .toMat(BroadcastHub.sink)(Keep.both)
            .run()

          val sessionTransport = new PekkoHttpStreamableMcpSessionTransport(sessionId, queue, jsonMapper)

          try {
            session.responseStream(request, sessionTransport)
              .contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext))
              .block()
          } catch {
            case ex: Exception =>
              logger.error(s"Failed to handle request stream: ${ex.getMessage}")
          }

          val eventStream = source.map(event => ByteString(event.toString))
          complete(
            HttpEntity.Chunked.fromData(
              MediaTypes.`text/event-stream`.toContentType,
              eventStream
            )
          )
        }
    }
  }

  /**
   * Handle DELETE requests for session deletion
   */
  private def handleDelete(): Route = {
    if (isClosing) {
      complete(StatusCodes.ServiceUnavailable -> "Server is shutting down")
    } else if (disallowDelete) {
      complete(StatusCodes.MethodNotAllowed)
    } else {
      extractRequest { httpRequest =>
        httpRequest.headers.find(_.name() == HttpHeaders.MCP_SESSION_ID).map(_.value()) match {
          case Some(sessionId) =>
            sessions.get(sessionId) match {
              case null =>
                complete(StatusCodes.NotFound)
              case session =>
                val transportContext = contextExtractor.extract(httpRequest)
                try {
                  session.delete().contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext)).block()
                  sessions.remove(sessionId)
                  listeningTransports.remove(sessionId)
                  complete(StatusCodes.OK)
                } catch {
                  case ex: Exception =>
                    logger.error(s"Failed to delete session $sessionId: ${ex.getMessage}")
                    complete(StatusCodes.InternalServerError -> ex.getMessage)
                }
            }
          case None =>
            complete(StatusCodes.BadRequest -> "Session ID required in mcp-session-id header")
        }
      }
    }
  }

  /**
   * Session transport implementation using Pekko Streams queue for SSE
   */
  private class PekkoHttpStreamableMcpSessionTransport(
    sessionId: String,
    queue: SourceQueueWithComplete[ServerSentEvent],
    jsonMapper: McpJsonMapper
  ) extends McpStreamableServerTransport {

    @volatile private var closed = false
    private val lock = new locks.ReentrantLock()

    override def sendMessage(message: McpSchema.JSONRPCMessage): Mono[Void] = {
      sendMessage(message, null)
    }

    override def sendMessage(message: McpSchema.JSONRPCMessage, messageId: String): Mono[Void] = {
      Mono.fromRunnable(() => {
        if (closed) {
          logger.debug(s"Attempted to send message to closed session: $sessionId")
          ()
        } else {

          lock.lock()
          try {
            if (closed) {
              logger.debug(s"Session $sessionId was closed during message send attempt")
              ()
            } else {

          val jsonText = jsonMapper.writeValueAsString(message)
          val eventId = if (messageId != null) messageId else sessionId
          val event = ServerSentEvent(data = jsonText, eventType = Some(MESSAGE_EVENT_TYPE), id = Some(eventId))

          val promise = Promise[Unit]()

          queue.offer(event).onComplete {
            case Success(QueueOfferResult.Enqueued) =>
              logger.debug(s"Message sent to session $sessionId with ID $eventId")
              promise.success(())
            case Success(QueueOfferResult.Dropped) =>
              logger.warn(s"Message dropped for session $sessionId (queue full)")
              promise.failure(new RuntimeException("Message queue full"))
            case Success(QueueOfferResult.QueueClosed) =>
              logger.debug(s"Queue closed for session $sessionId")
              closed = true
              promise.failure(new RuntimeException("Queue closed"))
            case Success(QueueOfferResult.Failure(ex)) =>
              logger.error(s"Failed to send message to session $sessionId", ex)
              promise.failure(ex)
            case Failure(ex) =>
              logger.error(s"Failed to offer message to queue for session $sessionId", ex)
              promise.failure(ex)
          }

              promise.future.onComplete(_ => ())
            }
          } catch {
            case ex: Exception =>
              logger.error(s"Failed to send message to session $sessionId: ${ex.getMessage}")
              sessions.remove(sessionId)
          } finally {
            lock.unlock()
          }
        }
      })
    }

    override def unmarshalFrom[T](data: Object, typeRef: TypeRef[T]): T = {
      jsonMapper.convertValue(data, typeRef)
    }

    override def closeGracefully(): Mono[Void] = {
      Mono.fromRunnable(() => close())
    }

    override def close(): Unit = {
      lock.lock()
      try {
        if (!closed) {
          closed = true
          queue.complete()
          logger.debug(s"Session transport $sessionId closed")
        }
      } finally {
        lock.unlock()
      }
    }
  }
}

object PekkoHttpStreamableServerTransportProvider {
  def builder(): Builder = new Builder()

  class Builder {
    private var jsonMapper: McpJsonMapper = _
    private var mcpEndpoint: String = "/mcp"
    private var disallowDelete: Boolean = false
    private var contextExtractor: McpTransportContextExtractor[HttpRequest] = _ => McpTransportContext.EMPTY
    private var host: String = "0.0.0.0"
    private var port: Int = 8082

    def jsonMapper(mapper: McpJsonMapper): Builder = {
      this.jsonMapper = mapper
      this
    }

    def mcpEndpoint(endpoint: String): Builder = {
      this.mcpEndpoint = endpoint
      this
    }

    def disallowDelete(disallow: Boolean): Builder = {
      this.disallowDelete = disallow
      this
    }

    def contextExtractor(extractor: McpTransportContextExtractor[HttpRequest]): Builder = {
      this.contextExtractor = extractor
      this
    }

    def host(h: String): Builder = {
      this.host = h
      this
    }

    def port(p: Int): Builder = {
      this.port = p
      this
    }

    def build()(implicit system: ActorSystem[?]): PekkoHttpStreamableServerTransportProvider = {
      val mapper = if (jsonMapper == null) McpJsonMapper.getDefault else jsonMapper
      new PekkoHttpStreamableServerTransportProvider(
        mapper,
        mcpEndpoint,
        disallowDelete,
        contextExtractor,
        host,
        port
      )
    }
  }
}
