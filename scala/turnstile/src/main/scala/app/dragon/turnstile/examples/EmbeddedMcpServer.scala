package app.dragon.turnstile.examples

import app.dragon.turnstile.actor.{ActorLookup, McpActor}
import app.dragon.turnstile.examples.{PekkoToSpringRequestAdapter, SpringToPekkoResponseAdapter}
import app.dragon.turnstile.examples.HeaderBasedRouter
import app.dragon.turnstile.service.ToolsService
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider
import io.modelcontextprotocol.server.{McpAsyncServer, McpServer}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.server.reactive.ServerHttpRequest.Builder
import org.springframework.http.server.reactive.{HttpHandler, ServerHttpRequest, ServerHttpResponse}
import org.springframework.http.{HttpMethod, HttpHeaders as SpringHeaders}
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

import java.net.{InetSocketAddress, URI}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Decoupled Embedded MCP Server using header-based routing to multiple WebFlux handlers.
 *
 * This example demonstrates a decoupled architecture where:
 * 1. Pekko HTTP server receives requests
 * 2. HeaderBasedRouter examines headers (like mcp-session-id)
 * 3. WebFluxHandlerRegistry provides the appropriate handler
 * 4. Request is forwarded to the selected WebFlux MCP transport
 *
 * Architecture:
 * {{{
 * Client Request
 *      ↓
 * Pekko HTTP Server
 *      ↓
 * HeaderBasedRouter (examines mcp-session-id)
 *      ↓
 * WebFluxHandlerRegistry
 *      ↓
 * Selected WebFlux HttpHandler
 *      ↓
 * PekkoToSpringRequestAdapter
 *      ↓
 * MCP Transport (Spring WebFlux)
 *      ↓
 * SpringToPekkoResponseAdapter
 *      ↓
 * Client Response
 * }}}
 *
 * Benefits:
 * - **Multi-tenancy**: Route different sessions to different MCP server instances
 * - **Load balancing**: Distribute load across multiple handlers
 * - **A/B testing**: Route to experimental vs stable implementations
 * - **Session affinity**: Maintain sticky sessions to specific handlers
 * - **Dynamic scaling**: Add/remove handlers at runtime
 *
 * Usage:
 * {{{
 * scala> sbt "runMain app.dragon.turnstile.example.EmbeddedMcpServer"
 * }}}
 */
object EmbeddedMcpServer {
  private val logger: Logger = LoggerFactory.getLogger(EmbeddedMcpServer.getClass)

  def main(args: Array[String]): Unit = {
    // Create actor system for Pekko HTTP
    implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
      Behaviors.empty,
      "turnstile"
    )
    implicit val ec: ExecutionContext = system.executionContext

    logger.info("Starting Decoupled Embedded MCP Server with Header-Based Routing")

    // Configuration
    val host = "0.0.0.0"
    val port = 8082
    val mcpEndpoint = "/mcp"

    try {

      // 3. Create HeaderBasedRouter with dynamic handler creation for session affinity
      val handlerFactory: (String => HttpHandler) = actorId =>
        EmbeddedMcpServer.createMcpServerAndHandler(
          serverName = actorId,
          serverVersion = "1.0.0",
          toolNamespace = "default"
        )._2
      
      val router = HeaderBasedRouter(
        handlerFactory = handlerFactory
      )

      logger.info(s"✓ Configured header-based router (dynamic handler creation)")
      logger.info(s"  Routing header: mcp-session-id")
      logger.info(s"  Fallback enabled: true")

      // 4. Create Pekko HTTP route with the router
      val route = createRoutedPekkoHttpRoute(router, mcpEndpoint)

      // 5. Start the Pekko HTTP server
      logger.info(s"Starting Pekko HTTP server on http://$host:$port$mcpEndpoint")
      val bindingFuture = Http().newServerAt(host, port).bind(route)

      bindingFuture.onComplete {
        case Success(binding) =>
          logger.info(s"✓ Decoupled MCP Server started successfully at http://$host:$port$mcpEndpoint")
          logger.info(s"✓ HTTP Server: Apache Pekko HTTP")
          logger.info(s"✓ MCP Transport: Spring WebFlux (multiple instances)")
          logger.info(s"✓ Routing Strategy: Header-based with session affinity")

          logger.info("")
          logger.info("Protocol endpoints:")
          logger.info(s"  POST http://$host:$port$mcpEndpoint - Initialize session, send requests")
          logger.info(s"  GET  http://$host:$port$mcpEndpoint - Establish SSE stream")
          logger.info(s"  DELETE http://$host:$port$mcpEndpoint - Close session")
          logger.info("")
          logger.info("Routing behavior:")
          logger.info("  - Requests without mcp-session-id → 'default' handler")
          logger.info("  - New sessions → routed to 'default' handler initially")
          logger.info("  - Sessions can be dynamically routed to different handlers")
          logger.info("")
          logger.info("Press ENTER to stop the server...")

        case Failure(ex) =>
          logger.error(s"✗ Failed to start MCP server: ${ex.getMessage}", ex)
          system.terminate()
      }

      // Wait for user input to shutdown
      scala.io.StdIn.readLine()

      logger.info("Shutting down MCP server...")
      
      // Terminate actor system
      system.terminate()
      Await.result(system.whenTerminated, 10.seconds)

      logger.info("✓ Decoupled Embedded MCP Server stopped")

    } catch {
      case ex: Exception =>
        logger.error("Failed to start decoupled embedded MCP server", ex)
        system.terminate()
        System.exit(1)
    }
  }

  /**
   * Create an MCP server instance with its HttpHandler.
   *
   * @return (McpAsyncServer, HttpHandler) tuple
   */
  def createMcpServerAndHandler(
    serverName: String,
    serverVersion: String,
    toolNamespace: String
  ): (McpAsyncServer, HttpHandler) = {
    val jsonMapper = McpJsonMapper.getDefault

    // Create WebFlux transport provider
    val transportProvider = WebFluxStreamableServerTransportProvider.builder()
      .jsonMapper(jsonMapper)
      .disallowDelete(false)
      .build()

    // Build MCP async server
    val mcpServer: McpAsyncServer = McpServer
      .async(transportProvider)
      .serverInfo(serverName, serverVersion)
      .capabilities(McpSchema.ServerCapabilities.builder()
        .resources(false, true)
        .tools(true)
        .prompts(true)
        .logging()
        .completions()
        .build())
      .build()

    // Register tools
    val toolsService = ToolsService.instance
    toolsService.getAsyncToolsSpec(toolNamespace).foreach { toolSpec =>
      mcpServer.addTool(toolSpec)
        .doOnSuccess(_ => logger.debug(s"[$serverName] Tool registered: ${toolSpec.tool().name()}"))
        .doOnError(ex => logger.error(s"[$serverName] Failed to register tool: ${toolSpec.tool().name()}", ex))
        .subscribe()
    }

    // Get HttpHandler from transport provider
    val routerFunction = transportProvider.getRouterFunction()
    val httpHandler = RouterFunctions.toHttpHandler(routerFunction)

    logger.info(s"✓ Created MCP server: $serverName v$serverVersion")

    (mcpServer, httpHandler)
  }

  /**
   * Create a Pekko HTTP route that uses HeaderBasedRouter to route to different handlers.
   */
  private def createRoutedPekkoHttpRoute(
    router: HeaderBasedRouter,
    mcpEndpoint: String
  )(implicit system: ActorSystem[?], ec: ExecutionContext): Route = {
    implicit val timeout: Timeout = Timeout(10.seconds)
    implicit val scheduler: Scheduler = system.scheduler
    implicit val sharding: ClusterSharding = ClusterSharding(system)
    path(mcpEndpoint.stripPrefix("/")) {
      extractRequest { pekkoRequest =>
        onSuccess(router.route(pekkoRequest)) {
          case routeResult: router.RouteLookupResult =>
            val mcpActorId = routeResult.mcpActorId
            val entityRef = ActorLookup.getMcpActor(mcpActorId)
            val askFuture = pekkoRequest.method.value match {
              case "GET" =>
                entityRef.ask[Either[McpActor.McpActorError, HttpResponse]](replyTo => McpActor.McpGetRequest(pekkoRequest, replyTo))
              case "POST" =>
                entityRef.ask[Either[McpActor.McpActorError, HttpResponse]](replyTo => McpActor.McpPostRequest(pekkoRequest, replyTo))
              case "DELETE" =>
                entityRef.ask[Either[McpActor.McpActorError, HttpResponse]](replyTo => McpActor.McpDeleteRequest(pekkoRequest, replyTo))
              case other =>
                // Return 405 Method Not Allowed for unsupported methods
                scala.concurrent.Future.successful(Left(McpActor.ProcessingError(s"Method $other not supported")))
            }
            onSuccess(askFuture) {
              case Right(httpResponse) => complete(httpResponse)
              case Left(McpActor.ProcessingError(msg)) => complete(HttpResponse(500, entity = msg))
            }
          case _ => complete(HttpResponse(404, entity = "Route not found"))
        }
      }
    }
  }
}

/**
 * Adapter that converts a Pekko HttpRequest to a Spring WebFlux ServerHttpRequest.
 */
class PekkoToSpringRequestAdapter(pekkoRequest: HttpRequest)(implicit system: ActorSystem[?], ec: ExecutionContext)
  extends ServerHttpRequest {

  private val logger = LoggerFactory.getLogger(classOf[PekkoToSpringRequestAdapter])

  override def getMethod(): HttpMethod = {
    pekkoRequest.method.name() match {
      case "GET" => HttpMethod.GET
      case "POST" => HttpMethod.POST
      case "PUT" => HttpMethod.PUT
      case "DELETE" => HttpMethod.DELETE
      case "PATCH" => HttpMethod.PATCH
      case "OPTIONS" => HttpMethod.OPTIONS
      case "HEAD" => HttpMethod.HEAD
      case other => HttpMethod.valueOf(other)
    }
  }

  override def getURI(): URI = {
    new URI(pekkoRequest.uri.toString())
  }

  override def getHeaders(): SpringHeaders = {
    val headers = new SpringHeaders()
    pekkoRequest.headers.foreach { header =>
      headers.add(header.name(), header.value())
    }
    // Add Content-Type from entity if present
    pekkoRequest.entity.contentType match {
      case ContentTypes.NoContentType =>
      case contentType =>
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(contentType.toString()))
    }
    headers
  }

  override def getBody(): Flux[org.springframework.core.io.buffer.DataBuffer] = {
    val dataBufferFactory = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance

    // Convert Pekko entity to Flux of DataBuffer
    pekkoRequest.entity match {
      case HttpEntity.Strict(_, data) =>
        if (data.isEmpty) {
          Flux.empty()
        } else {
          Flux.just(dataBufferFactory.wrap(data.toByteBuffer))
        }
      case HttpEntity.Default(_, _, dataStream) =>
        val publisher = dataStream
          .map(byteString => dataBufferFactory.wrap(byteString.toByteBuffer))
          .runWith(Sink.asPublisher(fanout = false))
        Flux.from(publisher)
      case HttpEntity.Chunked(_, chunks) =>
        val publisher = chunks
          .map(chunk => dataBufferFactory.wrap(chunk.data.toByteBuffer))
          .runWith(Sink.asPublisher(fanout = false))
        Flux.from(publisher)
      case _ =>
        Flux.empty()
    }
  }

  override def getId(): String = pekkoRequest.uri.toString()

  override def getRemoteAddress(): InetSocketAddress = {
    // Pekko HTTP doesn't expose remote address in HttpRequest directly
    new InetSocketAddress("0.0.0.0", 0)
  }

  override def getCookies(): org.springframework.util.MultiValueMap[String, org.springframework.http.HttpCookie] = {
    new org.springframework.util.LinkedMultiValueMap[String, org.springframework.http.HttpCookie]()
  }

  override def getSslInfo(): org.springframework.http.server.reactive.SslInfo = null

  override def mutate(): ServerHttpRequest.Builder = throw new UnsupportedOperationException("mutate not supported")

  override def getAttributes(): java.util.Map[String, Object] = {
    new java.util.HashMap[String, Object]()
  }

  override def getPath(): org.springframework.http.server.RequestPath = {
    org.springframework.http.server.RequestPath.parse(pekkoRequest.uri.path.toString(), null)
  }

  override def getQueryParams(): org.springframework.util.MultiValueMap[String, String] = {
    val queryParams = new org.springframework.util.LinkedMultiValueMap[String, String]()
    pekkoRequest.uri.query().foreach { case (key, value) =>
      queryParams.add(key, value)
    }
    queryParams
  }
}

/**
 * Adapter that converts a Spring WebFlux ServerHttpResponse to a Pekko HttpResponse.
 */
class SpringToPekkoResponseAdapter()(implicit system: ActorSystem[?], ec: ExecutionContext)
  extends ServerHttpResponse {

  private val logger = LoggerFactory.getLogger(classOf[SpringToPekkoResponseAdapter])
  private val statusCodeRef = new AtomicReference[org.springframework.http.HttpStatusCode](org.springframework.http.HttpStatus.OK)
  private val headersRef = new AtomicReference[SpringHeaders](new SpringHeaders())
  private val responsePromise = Promise[HttpResponse]()
  private val dataBufferFactory = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance

  override def setStatusCode(status: org.springframework.http.HttpStatusCode): Boolean = {
    statusCodeRef.set(status)
    true
  }

  override def getStatusCode(): org.springframework.http.HttpStatusCode = statusCodeRef.get()

  override def getHeaders(): SpringHeaders = headersRef.get()

  override def writeWith(body: org.reactivestreams.Publisher[? <: org.springframework.core.io.buffer.DataBuffer]): reactor.core.publisher.Mono[Void] = {
    // Convert Spring DataBuffer stream to Pekko Source
    val bodySource = Source.fromPublisher(body)
      .map { dataBuffer =>
        val byteBuffer = dataBuffer.asByteBuffer()
        val bytes = new Array[Byte](byteBuffer.remaining())
        byteBuffer.get(bytes)
        org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer)
        ByteString(bytes)
      }

    // Build Pekko HttpResponse
    val statusCode = statusCodeRef.get().value()
    val pekkoStatus = StatusCode.int2StatusCode(statusCode)

    // Convert Spring headers to Pekko headers, but exclude Content-Type and Content-Length (set via entity)
    val pekkoHeaders = headersRef.get().asScala.flatMap { case (name, values) =>
      if (name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Content-Length")) Nil
      else values.asScala.map(value => headers.RawHeader(name, value))
    }.toList

    // Determine content type
    val contentType = Option(headersRef.get().getContentType)
      .map(mt => ContentType.parse(mt.toString).toOption.getOrElse(ContentTypes.`application/octet-stream`))
      .getOrElse(ContentTypes.`application/octet-stream`)

    val response = HttpResponse(
      status = pekkoStatus,
      headers = pekkoHeaders,
      entity = HttpEntity(contentType, bodySource)
    )

    responsePromise.success(response)

    reactor.core.publisher.Mono.empty()
  }

  override def writeAndFlushWith(body: org.reactivestreams.Publisher[? <: org.reactivestreams.Publisher[? <: org.springframework.core.io.buffer.DataBuffer]]): reactor.core.publisher.Mono[Void] = {
    writeWith(Flux.from(body).flatMap(p => Flux.from(p)))
  }

  override def setComplete(): reactor.core.publisher.Mono[Void] = {
    if (!responsePromise.isCompleted) {
      // Empty response
      val statusCode = statusCodeRef.get().value()
      val pekkoStatus = StatusCode.int2StatusCode(statusCode)

      val pekkoHeaders = headersRef.get().asScala.flatMap { case (name, values) =>
        values.asScala.map(value => headers.RawHeader(name, value))
      }.toList

      val response = HttpResponse(
        status = pekkoStatus,
        headers = pekkoHeaders,
        entity = HttpEntity.Empty
      )

      responsePromise.success(response)
    }
    reactor.core.publisher.Mono.empty()
  }

  override def bufferFactory(): org.springframework.core.io.buffer.DataBufferFactory = dataBufferFactory

  override def getCookies(): org.springframework.util.MultiValueMap[String, org.springframework.http.ResponseCookie] = {
    new org.springframework.util.LinkedMultiValueMap[String, org.springframework.http.ResponseCookie]()
  }

  override def addCookie(cookie: org.springframework.http.ResponseCookie): Unit = {
    // Not implemented for this adapter
  }

  override def beforeCommit(action: java.util.function.Supplier[? <: reactor.core.publisher.Mono[Void]]): Unit = {
    // Not implemented for this adapter
  }

  override def isCommitted(): Boolean = {
    responsePromise.isCompleted
  }

  /**
   * Get the converted Pekko HttpResponse
   */
  def getPekkoResponse(): Future[HttpResponse] = responsePromise.future
}
