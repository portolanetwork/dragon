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
import org.apache.pekko.http.scaladsl.server.{Directive0, ExceptionHandler, Rejection, RejectionHandler, Route, StandardRoute}
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Source, SourceQueueWithComplete}
import org.apache.pekko.stream.{OverflowStrategy, QueueOfferResult}
import org.apache.pekko.util.ByteString
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono
import scalapb.options.ScalapbProto.message

import java.time.Duration
import java.util.{UUID, concurrent as juc}
import java.util.concurrent.{ConcurrentHashMap, locks}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import java.security.{KeyStore, SecureRandom}
import java.io.{FileInputStream, InputStream}

/**
 * SSL/TLS Configuration for HTTPS support
 */
case class SslConfig(
  enabled: Boolean,
  keyStorePath: String,
  keyStorePassword: String,
  keyStoreType: String = "PKCS12"
)

/**
 * Pekko HTTP implementation of MCP Streamable HTTP transport (MCP 2025-03-26).
 *
 * This is the Pekko HTTP equivalent of HttpServletStreamableServerTransportProvider,
 * providing Streamable HTTP transport for the Model Context Protocol using:
 * - HTTP POST for client-to-server messages (with Accept: application/json, text/event-stream)
 * - HTTP GET for establishing server-to-client SSE streams (with Accept: text/event-stream)
 * - Single unified endpoint supporting both POST and GET methods
 * - Session-based communication with mcp-session-id headers
 * - Message replay support using Last-Event-ID
 * - Optional HTTPS/TLS support for secure communication
 *
 * Streamable HTTP Transport (replaces deprecated HTTP+SSE from MCP 2024-11-05):
 * This transport consolidates the previous separate endpoints into a single MCP endpoint,
 * using Server-Sent Events (SSE) as the underlying streaming mechanism but with improved
 * session management and a unified request/response model.
 *
 * Architecture:
 * - POST /mcp: Send messages (initialize creates session, others require mcp-session-id)
 *   - Accept header must include both "application/json" and "text/event-stream"
 *   - Returns HTTP 202 for notifications/responses (no body)
 *   - Returns text/event-stream for request responses (may include intermediate messages)
 * - GET /mcp: Establish SSE connection for server-initiated messages
 *   - Accept header must include "text/event-stream"
 *   - Requires mcp-session-id header
 *   - Supports Last-Event-ID for message replay
 * - DELETE /mcp: Delete session (optional)
 *   - Requires mcp-session-id header
 *
 * Cloud Connector Compatibility:
 * - /messages endpoint provides compatibility with cloud-based MCP connectors
 * - Both /mcp and /messages endpoints support identical operations
 * - Session management works identically across both endpoints
 *
 * HTTPS Support:
 * - Optional SSL/TLS encryption using Java KeyStore
 * - Configurable via SslConfig
 * - Supports PKCS12, JKS, and other KeyStore formats
 *
 * CORS Support for Remote Connectors:
 * - Automatic CORS headers for cross-origin requests (ngrok, cloud hosting, etc.)
 * - OPTIONS preflight request handling
 * - Exposes mcp-session-id header to clients
 * - Configured for "*" origin (can be restricted for production)
 *
 * Protocol Compliance:
 * - Supports MCP protocol versions: 2024-11-05, 2025-03-26, 2025-06-18
 * - Implements Streamable HTTP transport specification (2025-03-26)
 * - Session IDs are globally unique and cryptographically secure (UUID)
 *
 * @param jsonMapper The JSON mapper for MCP message serialization
 * @param mcpEndpoint The endpoint path (default: "/mcp")
 * @param disallowDelete Whether to disallow DELETE requests
 * @param contextExtractor Extractor for transport context from HTTP requests
 * @param host Bind host address
 * @param port Bind port
 * @param baseUrl Optional base URL for endpoint announcements
 * @param keepAliveInterval Optional keep-alive ping interval
 * @param sslConfig Optional SSL/TLS configuration for HTTPS
 * @param system The Pekko ActorSystem
 */
class PekkoHttpStreamableServerTransportProvider(
  jsonMapper: McpJsonMapper,
  mcpEndpoint: String,
  disallowDelete: Boolean,
  contextExtractor: McpTransportContextExtractor[HttpRequest],
  host: String,
  port: Int,
  baseUrl: Option[String],
  keepAliveInterval: Option[FiniteDuration],
  sslConfig: Option[SslConfig]
)(implicit system: ActorSystem[?]) extends McpStreamableServerTransportProvider {
  private val logger: Logger = LoggerFactory.getLogger(classOf[PekkoHttpStreamableServerTransportProvider])
  private implicit val ec: ExecutionContext = system.executionContext

  // Event types
  private val MESSAGE_EVENT_TYPE = "message"
  private val ENDPOINT_EVENT_TYPE = "endpoint"
  private val PING_EVENT_TYPE = "ping"

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

  // Keep-alive scheduler
  private val keepAliveScheduler: Option[juc.ScheduledExecutorService] = keepAliveInterval.map { interval =>
    val scheduler = juc.Executors.newSingleThreadScheduledExecutor()
    scheduler.scheduleAtFixedRate(
      () => sendKeepAlive(),
      interval.toMillis,
      interval.toMillis,
      juc.TimeUnit.MILLISECONDS
    )
    scheduler
  }

  // Global ExceptionHandler to ensure all errors return application/json
  private val jsonExceptionHandler = ExceptionHandler {
    case ex: Exception =>
      extractUri { uri =>
        val errorJson = s"{\"error\":\"${ex.getMessage.replaceAll("\"", "'".toString)}\"}"
        complete(HttpResponse(
          StatusCodes.InternalServerError,
          entity = HttpEntity(ContentTypes.`application/json`, errorJson)
        ))
      }
  }

  // Global RejectionHandler to ensure all rejections return application/json
  private val jsonRejectionHandler = RejectionHandler.newBuilder()
    .handleNotFound {
      complete(HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(ContentTypes.`application/json`, "{\"error\":\"Not Found\"}")
      ))
    }
    .handleAll[Rejection] { rejections =>
      val errorMsg = rejections.map(_.toString).mkString(", ")
      complete(HttpResponse(
        StatusCodes.BadRequest,
        entity = HttpEntity(ContentTypes.`application/json`, s"{\"error\":\"$errorMsg\"}")
      ))
    }
    .result()

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

      // Stop keep-alive scheduler
      keepAliveScheduler.foreach { scheduler =>
        try {
          scheduler.shutdown()
          if (!scheduler.awaitTermination(5, juc.TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
          }
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to stop keep-alive scheduler: ${ex.getMessage}")
        }
      }

      // Close all transports first
      listeningTransports.values().asScala.foreach { transport =>
        try {
          transport.forceClose()
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to close transport: ${ex.getMessage}")
        }
      }

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
      listeningTransports.clear()

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
   * Send keep-alive ping to all active sessions
   */
  private def sendKeepAlive(): Unit = {
    if (!isClosing && !listeningTransports.isEmpty) {
      logger.trace(s"Sending keep-alive ping to ${listeningTransports.size()} sessions")
      listeningTransports.values().asScala.foreach { transport =>
        try {
          transport.sendPing()
        } catch {
          case ex: Exception =>
            logger.debug(s"Failed to send keep-alive ping: ${ex.getMessage}")
        }
      }
    }
  }

  /**
   * Create a JSON error response using McpError format
   */
  private def createErrorResponse(status: StatusCode, message: String, code: Int = -32603): Route = {
    val error = new McpSchema.JSONRPCResponse.JSONRPCError(code, message, null)
    val errorResponse = jsonMapper.writeValueAsString(error)
    complete(
      HttpResponse(
        status,
        entity = HttpEntity(ContentTypes.`application/json`, errorResponse)
      )
    )
  }

  /**
   * Create a plain text error response for SSE endpoints
   */
  private def createSseErrorResponse(status: StatusCode, message: String): Route = {
    complete(
      HttpResponse(
        status,
        entity = HttpEntity(ContentTypes.`application/json`, message)
      )
    )
  }

  /**
   * Create SSL context from KeyStore for HTTPS support
   */
  private def createSslContext(config: SslConfig): Try[SSLContext] = Try {
    logger.info(s"Loading SSL certificate from: ${config.keyStorePath}")

    // Load KeyStore
    val keyStore = KeyStore.getInstance(config.keyStoreType)
    val keyStoreStream: InputStream = new FileInputStream(config.keyStorePath)
    try {
      keyStore.load(keyStoreStream, config.keyStorePassword.toCharArray)
    } finally {
      keyStoreStream.close()
    }

    // Initialize KeyManagerFactory
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, config.keyStorePassword.toCharArray)

    // Initialize TrustManagerFactory
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(keyStore)

    // Create SSLContext
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagerFactory.getKeyManagers,
      trustManagerFactory.getTrustManagers,
      new SecureRandom
    )

    logger.info("SSL context initialized successfully")
    sslContext
  }

  /**
   * Start the HTTP/HTTPS server
   */
  def start(): Future[Http.ServerBinding] = {
    val route = createRoute()

    val bindingFuture = sslConfig match {
      case Some(config) if config.enabled =>
        // HTTPS mode
        createSslContext(config) match {
          case Success(sslContext) =>
            logger.info(s"Starting HTTPS Streaming MCP Server on https://$host:$port$mcpEndpoint")
            val httpsConnectionContext = org.apache.pekko.http.scaladsl.ConnectionContext.httpsServer(sslContext)
            Http()(system).newServerAt(host, port).enableHttps(httpsConnectionContext).bind(route)

          case Failure(ex) =>
            logger.error("Failed to create SSL context, falling back to HTTP", ex)
            // Fallback to HTTP
            Http()(system).newServerAt(host, port).bind(route)
        }

      case _ =>
        // HTTP mode
        logger.info(s"Starting HTTP Streaming MCP Server on http://$host:$port$mcpEndpoint")
        Http()(system).newServerAt(host, port).bind(route)
    }

    bindingFuture.onComplete {
      case Success(b) =>
        binding = Some(b)
        val protocol = if (sslConfig.exists(_.enabled)) "https" else "http"
        logger.info(s"Streaming MCP Server bound to $protocol://$host:$port$mcpEndpoint")
      case Failure(ex) =>
        logger.error(s"Failed to bind Streaming MCP Server to $host:$port", ex)
    }

    bindingFuture
  }

  /**
   * Add CORS headers to response for remote MCP connector support
   */
  private def addCorsHeaders: Directive0 = {
    respondWithHeaders(
      RawHeader("Access-Control-Allow-Origin", "*"),
      RawHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS"),
      RawHeader("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, x-api-key, mcp-session-id, last-event-id, cache-control"),
      RawHeader("Access-Control-Expose-Headers", "Content-Type, mcp-session-id"),
      RawHeader("Access-Control-Max-Age", "86400")
    )
  }

  /**
   * Handle OPTIONS requests for CORS preflight
   */
  private def handleOptions(): Route = {
    complete(
      HttpResponse(
        StatusCodes.NoContent,
        headers = List(
          RawHeader("Access-Control-Allow-Origin", "*"),
          RawHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS"),
          RawHeader("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, x-api-key, mcp-session-id, last-event-id, cache-control"),
          RawHeader("Access-Control-Expose-Headers", "Content-Type, mcp-session-id"),
          RawHeader("Access-Control-Max-Age", "86400")
        )
      )
    )
  }

  /**
   * Create the Pekko HTTP route handling GET, POST, DELETE, OPTIONS
   */
  private def createRoute(): Route = {
    handleExceptions(jsonExceptionHandler) {
      handleRejections(jsonRejectionHandler) {
        addCorsHeaders {
          path(mcpEndpoint.stripPrefix("/")) {
            options {
              handleOptions()
            } ~
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
      }
    }
  }

  /**
   * Handle GET requests for establishing SSE streams (Streamable HTTP transport).
   *
   * Per MCP Streamable HTTP spec (2025-03-26):
   * - Requires Accept: text/event-stream header
   * - Requires mcp-session-id header (session must already exist from POST initialize)
   * - Supports Last-Event-ID for message replay
   * - Returns SSE stream for server-initiated messages
   */
  private def handleGet(): Route = {
    if (isClosing) {
      logger.warn("GET request rejected: server is shutting down")
      createSseErrorResponse(StatusCodes.ServiceUnavailable, "Server is shutting down")
    } else {
      optionalHeaderValueByName("Accept") { acceptOpt =>
        optionalHeaderValueByName(HttpHeaders.MCP_SESSION_ID) { sessionIdOpt =>
          optionalHeaderValueByName("Last-Event-ID") { lastEventIdOpt =>
            (acceptOpt, sessionIdOpt) match {
              case (_, None) =>
                // No session ID - must initialize first via POST
                logger.warn(s"GET request rejected: missing mcp-session-id header. Accept: $acceptOpt")
                createSseErrorResponse(StatusCodes.BadRequest, "Session ID required in mcp-session-id header")
              case (None, Some(sessionId)) =>
                // No Accept header - require text/event-stream
                logger.warn(s"GET request rejected: missing Accept header. Session: $sessionId")
                createSseErrorResponse(StatusCodes.BadRequest, "text/event-stream required in Accept header")
              case (Some(accept), Some(sessionId)) if !accept.toLowerCase.contains("text/event-stream") =>
                // Accept header present but wrong type
                logger.warn(s"GET request rejected: invalid Accept header: $accept. Session: $sessionId")
                createSseErrorResponse(StatusCodes.BadRequest, s"text/event-stream required in Accept header, got: $accept")
              case (Some(accept), Some(sessionId)) =>
                // Valid Accept header and session ID
                logger.info(s"GET request: establishing SSE stream for session $sessionId")
                sessions.get(sessionId) match {
                  case null =>
                    logger.warn(s"GET request rejected: session not found: $sessionId. Active sessions: ${sessions.size()}")
                    createSseErrorResponse(StatusCodes.NotFound, s"Session not found: $sessionId")
                  case session =>
                    logger.info(s"SSE stream established for session $sessionId")
                    handleSseStream(session, sessionId, lastEventIdOpt)
                }
            }
          }
        }
      }
    }
  }

  /**
   * Handle SSE stream for a session (Streamable HTTP GET endpoint).
   *
   * Establishes a Server-Sent Events stream for server-to-client messages.
   * Supports message replay via Last-Event-ID header.
   */
  private def handleSseStream(session: McpStreamableServerSession, sessionId: String, lastEventIdOpt: Option[String]): Route = {
    extractRequest { httpRequest =>
      val transportContext = contextExtractor.extract(httpRequest)

      // Create SSE stream transport
      val (queue, source) = Source.queue[ServerSentEvent](bufferSize = 100, OverflowStrategy.backpressure)
        .toMat(BroadcastHub.sink)(Keep.both)
        .run()

      val sessionTransport = new PekkoHttpStreamableMcpSessionTransport(sessionId, queue, jsonMapper)

      // Send initial endpoint event if this is a new connection (not a replay)
      if (lastEventIdOpt.isEmpty) {
        val endpointUrl = baseUrl.getOrElse(s"http://$host:$port") + mcpEndpoint
        val endpointEvent = ServerSentEvent(data = endpointUrl, eventType = Some(ENDPOINT_EVENT_TYPE))
        queue.offer(endpointEvent)
      }

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

      val eventStream = source.map { event =>
        // Proper SSE formatting: data: <payload>\n\n
        val sb = new StringBuilder
        sb.append(s"data: ${event.data}\n")
        event.eventType.foreach(et => sb.append(s"event: $et\n"))
        event.id.foreach(id => sb.append(s"id: $id\n"))
        sb.append("\n")
        ByteString(sb.toString)
      }
      complete(
        HttpResponse(
          StatusCodes.OK,
          headers = List(
            RawHeader("Cache-Control", "no-cache"),
          ),
          entity = HttpEntity.Chunked.fromData(
            MediaTypes.`text/event-stream`.toContentType,
            eventStream
          )
        )
      )
    }
  }

  /**
   * Handle POST requests for client messages (Streamable HTTP transport).
   *
   * Per MCP Streamable HTTP spec (2025-03-26):
   * - Requires Accept header with both "application/json" and "text/event-stream"
   * - Initialize request: Creates new session, returns JSON response with mcp-session-id header
   * - Other messages: Require existing mcp-session-id header
   * - Responses/notifications: Return HTTP 202 (Accepted) with no body
   * - Requests: Return text/event-stream with response messages
   */
  private def handlePost(): Route = {
    if (isClosing) {
      // Explicitly set Content-Type to application/json for service unavailable
      complete(HttpResponse(StatusCodes.ServiceUnavailable, entity = HttpEntity(ContentTypes.`application/json`, "{\"error\":\"Server is shutting down\"}")))
    } else {
      extractRequest { httpRequest =>
        optionalHeaderValueByName(HttpHeaders.ACCEPT) { acceptOpt =>
          entity(as[String]) { body =>
            logger.debug(s"POST request received with body: $body")
            acceptOpt match {
              case Some(accept) if accept.toLowerCase.contains("text/event-stream") =>
                handlePostWithBody(httpRequest, body, accept.toLowerCase.contains("application/json"))
              case _ =>
                logger.warn(s"POST request rejected: invalid Accept header: $acceptOpt")
                // Explicitly set Content-Type to application/json for bad request
                complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`application/json`, "{\"error\":\"text/event-stream required in Accept header\"}")))
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

      val allHeaders = httpRequest.headers.map(h => s"${h.name()}=${h.value()}").mkString(", ")
      logger.debug(s"POST Headers: $allHeaders")
      logger.debug(s"Received POST message: ${message.getClass.getSimpleName}, method: ${message match {
        case req: McpSchema.JSONRPCRequest => req.method()
        case notif: McpSchema.JSONRPCNotification => notif.method()
        case _ => "N/A"
      }}")

      message match {
        // Handle initialize request
        case request: McpSchema.JSONRPCRequest if request.method() == McpSchema.METHOD_INITIALIZE =>
          if (!acceptsJson) {
            // Explicitly set Content-Type to application/json for bad request
            createErrorResponse(StatusCodes.BadRequest, "application/json required in Accept header for initialize", -32600)

          } else {
            val initRequest = jsonMapper.convertValue(request.params(), new TypeRef[McpSchema.InitializeRequest]() {})
            val init = sessionFactory.startSession(initRequest)

            // Use the session ID generated by the SDK's session factory
            val sessionId = init.session().getId
            sessions.put(sessionId, init.session())

            logger.info(s"Created new session: $sessionId")

            try {
              val initResult = init.initResult().block()
              val jsonResponse = jsonMapper.writeValueAsString(
                new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), initResult, null)
              )

              complete(
                HttpResponse(
                  StatusCodes.OK,
                  headers = List(RawHeader(HttpHeaders.MCP_SESSION_ID, sessionId)),
                  entity = HttpEntity(ContentTypes.`application/json`, jsonResponse)
                )
              )
            } catch {
              case ex: Exception =>
                logger.error(s"Failed to initialize session: ${ex.getMessage}")
                sessions.remove(sessionId)  // Clean up on failure
                createErrorResponse(StatusCodes.InternalServerError, s"Failed to initialize: ${ex.getMessage}")
            }
          }

        // Handle other requests (require session ID)
        case _ =>
          val sessionIdOpt = httpRequest.headers.find(_.name().equalsIgnoreCase(HttpHeaders.MCP_SESSION_ID)).map(_.value())
          logger.debug(s"Non-initialize message received, session ID present: ${sessionIdOpt.isDefined}, active sessions: ${sessions.size()}")

          sessionIdOpt match {
            case Some(sessionId) =>
              sessions.get(sessionId) match {
                case null =>
                  logger.warn(s"Session not found: $sessionId, active sessions: ${sessions.keySet().asScala.mkString(", ")}")
                  createErrorResponse(StatusCodes.NotFound, s"Session not found: $sessionId", -32001)
                case session =>
                  handleSessionMessage(session, message, transportContext, sessionId)
              }
            case None =>
              // TODO: Not sure about this!

              // Workaround for buggy clients that don't include mcp-session-id header
              // (e.g., MCP Inspector in proxy mode may strip headers)
              // Per spec, session ID SHOULD be included, but for robustness in single-session
              // scenarios, we'll use the only active session if exactly one exists.
              // This maintains compatibility with tools like MCP Inspector while still
              // requiring session IDs in multi-session environments.
              if (sessions.size() == 1) {
                val entry = sessions.entrySet().iterator().next()
                val sessionId = entry.getKey
                val session = entry.getValue
                logger.warn(s"Session ID missing in ${message.getClass.getSimpleName} message, using single active session: $sessionId")
                handleSessionMessage(session, message, transportContext, sessionId)
              } else {
                logger.warn(s"Session ID missing in ${message.getClass.getSimpleName} message and ${sessions.size()} sessions active")
                createErrorResponse(StatusCodes.BadRequest, "Session ID required in mcp-session-id header", -32602)
              }
          }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to deserialize message: ${ex.getMessage}")
        // Explicitly set Content-Type to application/json for deserialization errors
        complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`application/json`, s"{\"error\":\"Invalid message format: ${ex.getMessage}\"}")))
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
        //complete(StatusCodes.Accepted)
        //acceptedJson("ack")
        completeAccepted()

      case notification: McpSchema.JSONRPCNotification =>
        session.accept(notification).contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext)).block()
        //complete(StatusCodes.Accepted)
        //acceptedJson("ack")
        completeAccepted()

      case request: McpSchema.JSONRPCRequest =>
        // Use the listening transport if available, otherwise create per-request stream
        val transport = listeningTransports.get(sessionId)
        if (transport != null) {
          // Send response via the listening stream
          try {
            session.responseStream(request, transport)
              .contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext))
              .block()
            //complete(StatusCodes.Accepted)
            //acceptedJson("ack")
            completeAccepted()
          } catch {
            case ex: Exception =>
              logger.error(s"Failed to handle request: ${ex.getMessage}", ex)
              createErrorResponse(StatusCodes.InternalServerError, ex.getMessage)
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

          val eventStream = source.map { event =>
            // Proper SSE formatting: data: <payload>\n\n
            val sb = new StringBuilder
            sb.append(s"data: ${event.data}\n")
            event.eventType.foreach(et => sb.append(s"event: $et\n"))
            event.id.foreach(id => sb.append(s"id: $id\n"))
            sb.append("\n")
            ByteString(sb.toString)
          }
          complete(
            HttpResponse(
              StatusCodes.OK,
              headers = List(
                RawHeader("Cache-Control", "no-cache")
              ),
              entity = HttpEntity.Chunked.fromData(
                MediaTypes.`text/event-stream`.toContentType,
                eventStream
              )
            )
          )
        }
    }
  }


  private def completeAccepted(): StandardRoute =
    complete(
      HttpResponse(
        StatusCodes.Accepted,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          ""
        )
      )
    )


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
        httpRequest.headers.find(_.name().equalsIgnoreCase(HttpHeaders.MCP_SESSION_ID)).map(_.value()) match {
          case Some(sessionId) =>
            sessions.get(sessionId) match {
              case null =>
                complete(StatusCodes.NotFound)
              case session =>
                val transportContext = contextExtractor.extract(httpRequest)
                try {
                  // Close the transport first
                  Option(listeningTransports.remove(sessionId)).foreach(_.forceClose())

                  // Then delete the session
                  session.delete().contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext)).block()
                  sessions.remove(sessionId)
                  complete(StatusCodes.OK)
                } catch {
                  case ex: Exception =>
                    logger.error(s"Failed to delete session $sessionId: ${ex.getMessage}")
                    createErrorResponse(StatusCodes.InternalServerError, ex.getMessage)
                }
            }
          case None =>
            createErrorResponse(StatusCodes.BadRequest, "Session ID required in mcp-session-id header", -32602)
        }
      }
    }
  }

  /**
   * Session transport implementation for Streamable HTTP using Pekko Streams queue for SSE.
   *
   * This transport manages the SSE stream for a single session, formatting JSON-RPC messages
   * as Server-Sent Events according to the Streamable HTTP transport specification.
   */
  private class PekkoHttpStreamableMcpSessionTransport(
    sessionId: String,
    queue: SourceQueueWithComplete[ServerSentEvent],
    jsonMapper: McpJsonMapper
  ) extends McpStreamableServerTransport {

    @volatile private var closed = false
    @volatile private var shouldClose = false  // Track if we should close
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
              
              logger.debug(s"Sending message to session $sessionId: $jsonText")
              
          val promise = Promise[Unit]()

          queue.offer(event).onComplete {
            case Success(QueueOfferResult.Enqueued) =>
              logger.debug(s"Message sent to session $sessionId with ID $eventId")
              promise.success(())
            case Success(QueueOfferResult.Dropped) =>
              logger.warn(s"Message dropped for session $sessionId (queue full)")
              promise.failure(new RuntimeException("Message queue full"))
            case Success(QueueOfferResult.QueueClosed) =>
              logger.debug(s"Queue closed for session $sessionId (client likely disconnected)")
              closed = true
              // Do NOT call queue.complete() here; only close on explicit session close
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
      // Mark for closure but don't actually close immediately
      // The transport should stay open for the session lifetime
      logger.debug(s"Session transport $sessionId marked for graceful close (ignoring for persistent connection)")
      Mono.empty()
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

    /**
     * Send a ping event to keep the connection alive
     */
    def sendPing(): Unit = {
      if (!closed) {
        lock.lock()
        try {
          if (!closed) {
            val pingEvent = ServerSentEvent(data = "", eventType = Some(PING_EVENT_TYPE))
            queue.offer(pingEvent)
          }
        } finally {
          lock.unlock()
        }
      }
    }

    /**
     * Force close the transport (called during session deletion or server shutdown)
     */
    def forceClose(): Unit = {
      close()
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
    private var baseUrl: Option[String] = None
    private var keepAliveInterval: Option[FiniteDuration] = Some(30.seconds)
    private var sslConfig: Option[SslConfig] = None

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

    def baseUrl(url: String): Builder = {
      this.baseUrl = Some(url)
      this
    }

    def keepAliveInterval(interval: FiniteDuration): Builder = {
      this.keepAliveInterval = Some(interval)
      this
    }

    def disableKeepAlive(): Builder = {
      this.keepAliveInterval = None
      this
    }

    /**
     * Enable HTTPS with SSL/TLS configuration
     *
     * @param keyStorePath Path to the KeyStore file (PKCS12, JKS, etc.)
     * @param keyStorePassword Password for the KeyStore
     * @param keyStoreType KeyStore type (default: PKCS12)
     */
    def enableHttps(
      keyStorePath: String,
      keyStorePassword: String,
      keyStoreType: String = "PKCS12"
    ): Builder = {
      this.sslConfig = Some(SslConfig(
        enabled = true,
        keyStorePath = keyStorePath,
        keyStorePassword = keyStorePassword,
        keyStoreType = keyStoreType
      ))
      this
    }

    /**
     * Enable HTTPS with SSL configuration object
     */
    def withSslConfig(config: SslConfig): Builder = {
      this.sslConfig = Some(config)
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
        port,
        baseUrl,
        keepAliveInterval,
        sslConfig
      )
    }
  }
}
