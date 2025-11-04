package app.dragon.turnstile.gateway

import app.dragon.turnstile.actor.{ActorLookup, McpServerActor}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.*
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


class TurnstileMcpGateway(config: Config)(implicit system: ActorSystem[?]) {
  implicit val timeout: Timeout = Timeout(10.seconds)
  implicit val sharding: ClusterSharding = ClusterSharding(system)


  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpGateway])

  // Execution context for async operations
  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val sessionMap: SessionMap = new SessionMap()

  val serverVersion: String = config.getString("server-version")
  val host: String = config.getString("host")
  val port: Int = config.getInt("port")
  val mcpEndpoint: String = config.getString("mcp-endpoint")

  case class RouteLookupResult(
    mcpActorId: String,
    sessionIdOpt: Option[String]
  )


  def start(): Unit = {
    // Create actor system for Pekko HTTP
    try {
      logger.info(s"✓ Configured header-based router (dynamic handler creation)")
      logger.info(s"  Routing header: mcp-session-id")
      logger.info(s"  Fallback enabled: true")

      // 4. Create Pekko HTTP route with the router
      val route = createHttpRoute(mcpEndpoint)

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
          //logger.info("Press ENTER to stop the server...")

          // Provide the ClusterSharding instance explicitly to satisfy the implicit parameter
          //ActorLookup.getMcpClientActor("client-actor")(ClusterSharding(system)) ! McpListTools(system.ignoreRef)
          //ActorLookup.getMcpClientActor("client-actor") ! McpClientActor.Ping(system.ignoreRef)

        case Failure(ex) =>
          logger.error(s"✗ Failed to start MCP server: ${ex.getMessage}", ex)
          system.terminate()
      }
    } catch {
      case ex: Exception =>
        logger.error("Failed to start decoupled embedded MCP server", ex)
        system.terminate()
        System.exit(1)
    }
  }

  /**
   * Create a Pekko HTTP route that uses HeaderBasedRouter to route to different handlers.
   */
  private def createHttpRoute(
    mcpEndpoint: String
  ): Route = {
    path(mcpEndpoint.stripPrefix("/")) {
      extractRequest { pekkoRequest =>
        // Chain the lookup and the actor ask using flatMap/map so we can use map on Futures
        val responseFuture = for {
          routeResult <- sessionMap.lookup(pekkoRequest)
          // derived values can be bound inside the for comprehension
          mcpActorId = routeResult.mcpActorId
          //entityRef = ActorLookup.getMcpServerActor(routeResult.mcpActorId)
           askResult <- pekkoRequest.method.value match {
            case "GET" =>
              ActorLookup.getMcpServerActor(mcpActorId)
                .ask[Either[McpServerActor.McpActorError, HttpResponse]](replyTo => {
                  logger.debug(s"Routing GET request to MCP actor: $mcpActorId")
                  McpServerActor.McpGetRequest(pekkoRequest, replyTo)
                })
            case "POST" =>
              ActorLookup.getMcpServerActor(mcpActorId)
                .ask[Either[McpServerActor.McpActorError, HttpResponse]](replyTo => {
                logger.debug(s"Routing POST request to MCP actor: $mcpActorId")
                McpServerActor.McpPostRequest(pekkoRequest, replyTo)
              })
            case "DELETE" =>
              ActorLookup.getMcpServerActor(mcpActorId)
                .ask[Either[McpServerActor.McpActorError, HttpResponse]](replyTo => {
                logger.debug(s"Routing DELETE request to MCP actor: $mcpActorId")
                McpServerActor.McpDeleteRequest(pekkoRequest, replyTo)
              })
            case other =>
              logger.warn(s"Method $other not supported by MCP gateway")
              scala.concurrent.Future.successful(Left(McpServerActor.ProcessingError(s"Method $other not supported")))
          }
        } yield {
          askResult match {
            case right @ Right(httpResponse) =>
              val sessionIdOpt = httpResponse.headers.find(_.name.toLowerCase == "mcp-session-id").map(_.value)
              logger.debug(s"---- Received response from MCP actor: $mcpActorId")
              logger.debug(s"---- Updating session mapping: ${sessionIdOpt.orNull} -> $mcpActorId")
              sessionMap.updateSessionMapping(sessionIdOpt.getOrElse(""), mcpActorId)
              right
            case left @ Left(_) => left
          }
        }
        // end for-comprehension

        // Single onSuccess that handles the final Either result
        onSuccess(responseFuture) {
          case Right(httpResponse) =>
            complete(httpResponse)
          case Left(McpServerActor.ProcessingError(msg)) =>
            logger.error(s"Error processing request: $msg")
            complete(HttpResponse(500, entity = msg))
        }
       }
     }
   }


}

object TurnstileMcpGateway {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpGateway])

  def apply(config: Config)(implicit system: ActorSystem[?]): TurnstileMcpGateway = new TurnstileMcpGateway(config)
}