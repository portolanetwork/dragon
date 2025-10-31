package app.dragon.turnstile.server

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.gateway.TurnstileMcpGateway.logger
import app.dragon.turnstile.service.ToolsService
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider
import io.modelcontextprotocol.server.{McpAsyncServer, McpServer}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory
import org.springframework.http.server.reactive.{HttpHandler, ServerHttpRequest, ServerHttpResponse}
import org.springframework.http.{HttpMethod, HttpHeaders as SpringHeaders}
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Flux

import java.net.{InetSocketAddress, URI}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

object TurnstileStreamingHttpMcpServer {
  val logger: org.slf4j.Logger = LoggerFactory.getLogger(classOf[TurnstileStreamingHttpMcpServer])
  
  val serverName: String = ApplicationConfig.mcpStreaming.getString("server-name")
  val serverVersion: String = ApplicationConfig.mcpStreaming.getString("server-version")
  
  def apply(): TurnstileStreamingHttpMcpServer =
    new TurnstileStreamingHttpMcpServer(serverName, serverVersion, "default")
}

class TurnstileStreamingHttpMcpServer(
  val serverName: String,
  val serverVersion: String,
  val toolNamespace: String
) {
  private val logger: org.slf4j.Logger = LoggerFactory.getLogger(classOf[TurnstileStreamingHttpMcpServer])
  
  // These are set in start()
  private var mcpAsyncServer: Option[McpAsyncServer] = None
  private var httpHandler: Option[HttpHandler] = None
  private var started: Boolean = false // Track if the server has been started

  /**
   * Create an MCP server instance with its HttpHandler.
   *
   * @return (McpAsyncServer, HttpHandler) tuple
   */
  def start(): TurnstileStreamingHttpMcpServer = {
    if (started) {
      logger.warn(s"MCP server: $serverName v$serverVersion already started; ignoring duplicate start.")
      return this
    }
    started = true
    logger.info(s"Creating MCP server: $serverName v$serverVersion")

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
      mcpServer
        .addTool(toolSpec)
        .doOnSuccess(_ => logger.debug(s"[$serverName] Tool registered: ${toolSpec.tool().name()}"))
        .doOnError(ex => logger.error(s"[$serverName] Failed to register tool: ${toolSpec.tool().name()}", ex))
        .subscribe()
    }

    // Get HttpHandler from transport provider
    val routerFunction = transportProvider.getRouterFunction
    val httpHandler = RouterFunctions.toHttpHandler(routerFunction)

    logger.info(s"✓ Created MCP server: $serverName v$serverVersion")
    
    this.mcpAsyncServer = Some(mcpServer)
    this.httpHandler = Some(httpHandler)
    
    this
  }
  
  def stop(): Unit = {
    mcpAsyncServer.foreach { server =>
      logger.info(s"Stopping MCP server: $serverName v$serverVersion")
      server.close()
      logger.info(s"✓ Stopped MCP server: $serverName v$serverVersion")
    }
  }
  
  def getMcpAsyncServer: Option[McpAsyncServer] = mcpAsyncServer
  def getHttpHandler: Option[HttpHandler] = httpHandler
}
