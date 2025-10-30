package app.dragon.turnstile.examples

import app.dragon.turnstile.actor.{ActorLookup, McpActor}
import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.examples.{PekkoToSpringRequestAdapter, SpringToPekkoResponseAdapter}
import app.dragon.turnstile.examples.HeaderBasedRouter
import app.dragon.turnstile.service.ToolsService
import com.typesafe.config.Config
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
import org.apache.pekko.actor.typed.ActorSystem

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

/*
case class EmbeddedMcpServerConfig(
  host: String = "0.0.0.0",
  port: Int = 8082,
  mcpEndpoint: String = "/mcp",
  serverVersion: String = "1.0.0",
  toolNamespace: String = "default"
)
*/

class TurnstilMcpGateway(config: Config)(implicit system: ActorSystem[?]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstilMcpGateway])

  // Execution context for async operations
  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext


  val serverVersion: String = config.getString("server-version")
  val toolNamespace: String = "tool-namespace"
  val host: String = config.getString("host")
  val port: Int = config.getInt("port")
  val mcpEndpoint: String = config.getString("mcp-endpoint")

  def start(): Unit = {
    // Create actor system for Pekko HTTP

    /*
    implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
      Behaviors.empty,
      "turnstile"
    )
    implicit val ec: ExecutionContext = system.executionContext
     */

    logger.info("Starting Decoupled Embedded MCP Server with Header-Based Routing")

    try {
      // 3. Create HeaderBasedRouter with dynamic handler creation for session affinity

      /*
      val handlerFactory: String => HttpHandler = actorId =>
        EmbeddedMcpServer.createMcpServerAndHandler(
          serverName = actorId,
          serverVersion = serverVersion,
          toolNamespace = toolNamespace
        )._2

       */

      val router = HeaderBasedRouter(
        //handlerFactory = handlerFactory
      )

      logger.info(s"✓ Configured header-based router (dynamic handler creation)")
      logger.info(s"  Routing header: mcp-session-id")
      logger.info(s"  Fallback enabled: true")

      // 4. Create Pekko HTTP route with the router
      val route = TurnstilMcpGateway.createRoutedPekkoHttpRoute(router, mcpEndpoint)

      // 5. Start the Pekko HTTP server
      logger.info(s"Starting Pekko HTTP server on http://${host}:${port}${mcpEndpoint}")
      val bindingFuture = Http().newServerAt(host, port).bind(route)

      bindingFuture.onComplete {
        case Success(binding) =>
          logger.info(s"✓ Decoupled MCP Server started successfully at http://${host}:${port}${mcpEndpoint}")
          logger.info(s"✓ HTTP Server: Apache Pekko HTTP")
          logger.info(s"✓ MCP Transport: Spring WebFlux (multiple instances)")
          logger.info(s"✓ Routing Strategy: Header-based with session affinity")

          logger.info("")
          logger.info("Protocol endpoints:")
          logger.info(s"  POST http://${host}:${port}${mcpEndpoint} - Initialize session, send requests")
          logger.info(s"  GET  http://${host}:${port}${mcpEndpoint} - Establish SSE stream")
          logger.info(s"  DELETE http://${host}:${port}${mcpEndpoint} - Close session")
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
      //scala.io.StdIn.readLine()

      //logger.info("Shutting down MCP server...")
      // Terminate actor system
      //system.terminate()
      //Await.result(system.whenTerminated, 10.seconds)
      //logger.info("✓ Decoupled Embedded MCP Server stopped")
    } catch {
      case ex: Exception =>
        logger.error("Failed to start decoupled embedded MCP server", ex)
        system.terminate()
        System.exit(1)
    }
  }
}

object TurnstilMcpGateway {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstilMcpGateway])

  def apply(config: Config)(implicit system: ActorSystem[?]): TurnstilMcpGateway = new TurnstilMcpGateway(config)

  /*
  def main(args: Array[String]): Unit = {
    // Optionally parse args for config, here we use defaults

    EmbeddedMcpServer(ApplicationConfig.rootConfig.getConfig("turnstile.mcp-streaming")).start()
  }
   */

  /**
   * Create an MCP server instance with its HttpHandler.
   *
   * @return (McpAsyncServer, HttpHandler) tuple
   */
  /*
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
*/

  /*
  *     path(mcpEndpoint.stripPrefix("/")) {
      extractRequest { pekkoRequest =>
        complete {
          // Use router to select the appropriate handler
          router.route(pekkoRequest).flatMap {
            case Right(httpHandler) =>
              // Handler found - convert and execute
              val springRequest = new PekkoToSpringRequestAdapter(pekkoRequest)
              val springResponse = new SpringToPekkoResponseAdapter()

              val handlerMono = httpHandler.handle(springRequest, springResponse)

              // Convert Java CompletableFuture to Scala Future
              import scala.jdk.FutureConverters.*

              val javaFuture = handlerMono
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture

              javaFuture.asScala.flatMap { _ =>
                springResponse.getPekkoResponse()
              }

            case Left(errorResponse) =>
              // Router returned an error (no handler found)
              Future.successful(errorResponse)
          }
        }
      }
    }
  }
  * */
  
  
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
                entityRef.ask[Either[McpActor.McpActorError, HttpResponse]](replyTo => {
                  logger.debug(s"Routing GET request to MCP actor: $mcpActorId")
                  McpActor.McpGetRequest(pekkoRequest, replyTo)
                })
              case "POST" =>
                entityRef.ask[Either[McpActor.McpActorError, HttpResponse]](replyTo => {
                  logger.debug(s"Routing POST request to MCP actor: $mcpActorId")
                  McpActor.McpPostRequest(pekkoRequest, replyTo)
                })
              case "DELETE" =>
                entityRef.ask[Either[McpActor.McpActorError, HttpResponse]](replyTo => {
                  logger.debug(s"Routing DELETE request to MCP actor: $mcpActorId")
                  McpActor.McpDeleteRequest(pekkoRequest, replyTo)
                })
              case other =>
                // Return 405 Method Not Allowed for unsupported methods
                logger.warn(s"Method $other not supported by MCP gateway")

                scala.concurrent.Future.successful(Left(McpActor.ProcessingError(s"Method $other not supported")))
            }
            onSuccess(askFuture) {
              case Right(httpResponse) =>
                {
                  logger.debug(s"---- Received response from MCP actor: $mcpActorId")
                  val sessionIdOpt = httpResponse.headers.find(_.name.toLowerCase == "mcp-session-id").map(_.value)
                  
                  logger.debug(s"---- Updating session mapping: ${sessionIdOpt.orNull} -> $mcpActorId")
                  router.updateSessionMapping(sessionIdOpt.orNull, mcpActorId)
                  complete(httpResponse)
                }
              case Left(McpActor.ProcessingError(msg)) =>
              {
                logger.error(s"Error processing request in MCP actor $mcpActorId: $msg")
                complete(HttpResponse(500, entity = msg))
              }
            }
          case _ => complete(HttpResponse(404, entity = "Route not found"))
        }
      }
    }
  }
}

