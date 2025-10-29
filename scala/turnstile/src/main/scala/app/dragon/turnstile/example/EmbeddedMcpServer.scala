package app.dragon.turnstile.example

import app.dragon.turnstile.service.ToolsService
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider
import io.modelcontextprotocol.server.{McpAsyncServer, McpServer}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Sink, Source, StreamConverters}
import org.apache.pekko.util.ByteString
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.server.reactive.ServerHttpRequest.Builder
import org.springframework.http.server.reactive.{HttpHandler, ServerHttpRequest, ServerHttpResponse}
import org.springframework.http.{HttpMethod, HttpHeaders as SpringHeaders}
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

import java.net.{InetSocketAddress, URI}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Embedded MCP Server Example using WebFluxStreamableServerTransportProvider with Pekko HTTP.
 *
 * This example demonstrates how to:
 * 1. Create a WebFluxStreamableServerTransportProvider from the MCP Java SDK
 * 2. Bridge Spring WebFlux's reactive HTTP handling with Apache Pekko HTTP server
 * 3. Route requests from Pekko HTTP to the WebFlux MCP transport provider
 * 4. Handle bidirectional streaming between the two frameworks
 *
 * Architecture:
 * - Apache Pekko HTTP: HTTP server and routing layer
 * - Spring WebFlux: MCP transport provider (via WebFluxStreamableServerTransportProvider)
 * - Adapter layer: Converts between Pekko HTTP and Spring WebFlux request/response formats
 * - McpAsyncServer: MCP protocol server with async/reactive API (Project Reactor)
 * - ToolsService: Manages tool registration and handlers
 *
 * Why Bridge Two Frameworks?
 * This example shows how to integrate Spring WebFlux's MCP transport provider into a
 * Pekko-based application. This is useful when:
 * - Your application is built on Pekko/Akka but you want to use Spring's MCP transport
 * - You need to integrate with existing Pekko HTTP infrastructure
 * - You want to leverage Spring's MCP ecosystem while maintaining Pekko's actor model
 *
 * MCP Protocol Flow (Streamable HTTP):
 * 1. Client POST /mcp with initialize request → Server returns session ID
 * 2. Client GET /mcp with session ID → Server establishes SSE stream
 * 3. Client POST /mcp with session ID → Server processes requests
 * 4. Server sends responses via SSE stream
 *
 * Usage:
 * {{{
 * scala> sbt "runMain app.dragon.turnstile.examples.EmbeddedMcpServer"
 * }}}
 *
 * Test with curl:
 * {{{
 * # Initialize session
 * curl -X POST http://localhost:8082/mcp \
 *   -H "Content-Type: application/json" \
 *   -H "Accept: application/json, text/event-stream" \
 *   -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
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

    logger.info("Starting Embedded MCP Server Example with WebFlux Transport Provider")

    // Configuration
    val serverName = "Embedded Turnstile MCP Server"
    val serverVersion = "1.0.0"
    val host = "0.0.0.0"
    val port = 8082
    val mcpEndpoint = "/mcp"

    try {
      // 1. Create JSON mapper for MCP protocol serialization
      val jsonMapper = McpJsonMapper.getDefault

      // 2. Create Spring WebFlux transport provider
      //    This is the official MCP Java SDK transport for Spring WebFlux
      val transportProvider = WebFluxStreamableServerTransportProvider.builder()
        .jsonMapper(jsonMapper)
        .disallowDelete(false)
        .build()

      // 3. Build MCP async server with capabilities
      val mcpServer: McpAsyncServer = McpServer
        .async(transportProvider)
        .serverInfo(serverName, serverVersion)
        .capabilities(McpSchema.ServerCapabilities.builder()
          .resources(false, true)  // Enable resources with subscribe capability
          .tools(true)             // Enable tools
          .prompts(true)           // Enable prompts
          .logging()               // Enable logging
          .completions()           // Enable completions
          .build())
        .build()

      // 4. Register tools from ToolsService
      val toolsService = ToolsService.instance
      logger.info("Registering tools...")

      toolsService.getAsyncToolsSpec("default").foreach { toolSpec =>
        mcpServer.addTool(toolSpec)
          .doOnSuccess(_ => logger.info(s"Tool registered: ${toolSpec.tool().name()}"))
          .doOnError(ex => logger.error(s"Failed to register tool: ${toolSpec.tool().name()}", ex))
          .subscribe()
      }

      // 5. Get the Spring WebFlux RouterFunction from the transport provider
      val routerFunction = transportProvider.getRouterFunction()
      val httpHandler = RouterFunctions.toHttpHandler(routerFunction)

      // 6. Create Pekko HTTP route that adapts to Spring WebFlux HttpHandler
      val route = createPekkoToWebFluxRoute(httpHandler, mcpEndpoint)

      // 7. Start the Pekko HTTP server
      logger.info(s"Starting Pekko HTTP server on http://$host:$port$mcpEndpoint")
      logger.info(s"Using Spring WebFlux transport provider for MCP protocol")
      val bindingFuture = Http().newServerAt(host, port).bind(route)

      bindingFuture.onComplete {
        case Success(binding) =>
          logger.info(s"✓ MCP Server started successfully at http://$host:$port$mcpEndpoint")
          logger.info(s"✓ Server name: $serverName v$serverVersion")
          logger.info(s"✓ HTTP Server: Apache Pekko HTTP")
          logger.info(s"✓ MCP Transport: Spring WebFlux (via WebFluxStreamableServerTransportProvider)")

          // List registered tools
          val tools = mcpServer.listTools().collectList().block()
          logger.info(s"✓ Available tools (${tools.size()}): ${tools.asScala.map(_.name()).mkString(", ")}")

          logger.info("")
          logger.info("Protocol endpoints:")
          logger.info(s"  POST http://$host:$port$mcpEndpoint - Initialize session, send requests")
          logger.info(s"  GET  http://$host:$port$mcpEndpoint - Establish SSE stream")
          logger.info(s"  DELETE http://$host:$port$mcpEndpoint - Close session")
          logger.info("")
          logger.info("Press ENTER to stop the server...")

        case Failure(ex) =>
          logger.error(s"✗ Failed to start MCP server: ${ex.getMessage}", ex)
          system.terminate()
      }

      // Wait for user input to shutdown
      scala.io.StdIn.readLine()

      logger.info("Shutting down MCP server...")

      // 8. Graceful shutdown
      mcpServer.closeGracefully()
        .doOnSuccess(_ => logger.info("MCP server closed"))
        .doOnError(ex => logger.error("Error closing MCP server", ex))
        .subscribe()

      transportProvider.closeGracefully().block()

      // Terminate actor system
      system.terminate()
      Await.result(system.whenTerminated, 10.seconds)

      logger.info("✓ Embedded MCP Server stopped")

    } catch {
      case ex: Exception =>
        logger.error("Failed to start embedded MCP server", ex)
        system.terminate()
        System.exit(1)
    }
  }

  /**
   * Create a Pekko HTTP route that adapts requests to Spring WebFlux HttpHandler.
   *
   * This adapter converts between Pekko HTTP and Spring WebFlux's reactive HTTP abstractions:
   * - Pekko HttpRequest → Spring ServerHttpRequest
   * - Spring ServerHttpResponse → Pekko HttpResponse
   *
   * Supports:
   * - All HTTP methods (GET, POST, DELETE, OPTIONS)
   * - Request/response headers
   * - Streaming request bodies
   * - Streaming response bodies (including SSE)
   */
  private def createPekkoToWebFluxRoute(
    httpHandler: HttpHandler,
    mcpEndpoint: String
  )(implicit system: ActorSystem[?], ec: ExecutionContext): Route = {
    path(mcpEndpoint.stripPrefix("/")) {
      extractRequest { pekkoRequest =>
        // Convert Pekko request to Spring WebFlux ServerHttpRequest
        val springRequest = new PekkoToSpringRequestAdapter(pekkoRequest)
        val springResponse = new SpringToPekkoResponseAdapter()

        // Execute the Spring WebFlux handler
        val handlerMono = httpHandler.handle(springRequest, springResponse)

        // Convert the response to Pekko HTTP format
        complete {
          // Convert Java CompletableFuture to Scala Future
          import scala.jdk.FutureConverters.*

          val javaFuture = handlerMono
            .subscribeOn(Schedulers.boundedElastic())
            .toFuture

          javaFuture.asScala.flatMap { _ =>
            // Get the response that was written to our adapter
            springResponse.getPekkoResponse()
          }
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
    // Return a placeholder or extract from headers if needed
    new InetSocketAddress("0.0.0.0", 0)
  }

  // Unsupported optional methods
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

    // Convert Spring headers to Pekko headers, filtering out Content-Type and Content-Length
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

      // Convert Spring headers to Pekko headers, filtering out Content-Type and Content-Length
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
        entity = HttpEntity.Empty.withContentType(contentType)
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
