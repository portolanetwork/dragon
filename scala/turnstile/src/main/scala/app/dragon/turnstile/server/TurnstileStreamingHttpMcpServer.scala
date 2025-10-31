package app.dragon.turnstile.server

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.service.ToolsService
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider
import io.modelcontextprotocol.server.{McpAsyncServer, McpServer}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.web.reactive.function.server.RouterFunctions

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object TurnstileStreamingHttpMcpServer {
  val logger: org.slf4j.Logger = LoggerFactory.getLogger(classOf[TurnstileStreamingHttpMcpServer])

  val serverName: String = ApplicationConfig.mcpStreaming.getString("server-name")
  val serverVersion: String = ApplicationConfig.mcpStreaming.getString("server-version")

  def apply(userId: String)(implicit system: ActorSystem[?]): TurnstileStreamingHttpMcpServer =
    new TurnstileStreamingHttpMcpServer(serverName, serverVersion, userId)
}

class TurnstileStreamingHttpMcpServer(
  val serverName: String,
  val serverVersion: String,
  val userId: String,
)(implicit val system: ActorSystem[?]) {
  private val logger: org.slf4j.Logger = LoggerFactory.getLogger(classOf[TurnstileStreamingHttpMcpServer])
  // Execution context derived from the provided actor system
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val timeout: Timeout = 30.seconds

  // These are set in start()
  private var mcpAsyncServer: Option[McpAsyncServer] = None
  private var httpHandler: Option[HttpHandler] = None
  private var started: Boolean = false // Track if the server has been started

  /**
   * Create an MCP server instance with its HttpHandler.
   * Asynchronously fetches tools from both default sources and MCP client actors.
   *
   * @return Future[TurnstileStreamingHttpMcpServer] - completes when all tools are registered
   */
  def start(
  ): TurnstileStreamingHttpMcpServer = {
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

    ToolsService.getInstance(userId).getDefaultToolsSpec.foreach { toolSpec =>
      mcpServer
        .addTool(toolSpec)
        .doOnSuccess(_ => logger.debug(s"[$serverName] Tool registered: ${toolSpec.tool().name()}"))
        .doOnError(ex => logger.error(s"[$serverName] Failed to register tool: ${toolSpec.tool().name()}", ex))
        .subscribe()
    }

    val routerFunction = transportProvider.getRouterFunction
    val httpHandler = RouterFunctions.toHttpHandler(routerFunction)

    this.mcpAsyncServer = Some(mcpServer)
    this.httpHandler = Some(httpHandler)

    this
  }

  def refreshDownstreamTools(): Future[Unit] = {
    ToolsService.getInstance(userId)
      .getDownstreamToolsSpec("downstream-server").map {
        case Right(toolSpecs) =>
          logger.info(s"[$serverName] Refreshing ${toolSpecs.size} namespaced tools")
          toolSpecs.foreach { toolSpec =>
            mcpAsyncServer.foreach { server =>
              server
                .addTool(toolSpec)
                .doOnSuccess(_ => logger.debug(s"[$serverName] Namespaced tool registered: ${toolSpec.tool().name()}"))
                .doOnError(ex => logger.error(s"[$serverName] Failed to register namespaced tool: ${toolSpec.tool().name()}", ex))
                .subscribe()
            }
          }
        case Left(error) =>
          logger.error(s"[$serverName] Failed to fetch namespaced tools: $error")

      }.recover { case ex =>
        logger.error(s"Failed to refresh namespaced tools for MCP server: $serverName", ex)
      }
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
