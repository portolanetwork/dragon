/*
 * Copyright 2025 Sami Malik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: Sami Malik (sami.malik [at] portolanetwork.io)
 */

package app.dragon.turnstile.gateway

import app.dragon.turnstile.actor.{ActorLookup, McpServerActor}
import app.dragon.turnstile.auth.ServerAuthService
import app.dragon.turnstile.auth.ServerAuthService.{AccessDenied, MissingAuthHeader}
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


class TurnstileMcpGateway(
  mcpConfig: Config,
  authConfig: Config
)(
  implicit system: ActorSystem[?]
) {
  implicit val timeout: Timeout = Timeout(10.seconds)
  implicit val sharding: ClusterSharding = ClusterSharding(system)


  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpGateway])

  // Execution context for async operations
  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val sessionMap: SessionMap = new SessionMap()

  val serverVersion: String = mcpConfig.getString("server-version")
  val host: String = mcpConfig.getString("host")
  val port: Int = mcpConfig.getInt("port")
  val mcpEndpoint: String = mcpConfig.getString("mcp-endpoint")
  val auth0Domain: String = authConfig.getString("auth0.domain")

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
      val route =
        createWellKnownRoute() ~ createCallbackRoute() ~ createMcpRoute(mcpEndpoint)

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
   * Proxy .well-known requests to Auth0 domain.
   * This handles OIDC/OAuth2 discovery endpoints like:
   * - /.well-known/openid-configuration
   * - /.well-known/jwks.json
   */
  private def createWellKnownRoute(): Route = {
    pathPrefix(".well-known") {
      extractUnmatchedPath { remainingPath =>
        extractRequest { _ =>
          val path = s"/.well-known$remainingPath"
          val auth0Url = s"https://$auth0Domain$path"
          logger.debug(s"Proxying .well-known request to: $auth0Url")

          val proxyRequest = HttpRequest(
            method = HttpMethods.GET,
            uri = auth0Url
          )

          val responseFuture = Http()
            .singleRequest(proxyRequest)
            .map { response =>
              logger.debug(s"Received response from Auth0: ${response.status}")
              response
            }
            .recover {
              case ex: Exception =>
                logger.error(s"Error proxying to Auth0: ${ex.getMessage}", ex)
                HttpResponse(
                  status = StatusCodes.BadGateway,
                  entity = HttpEntity(
                    ContentTypes.`application/json`,
                    s"""{"error": "Failed to proxy to Auth0", "message": "${ex.getMessage}"}"""
                  )
                )
            }

          complete(responseFuture)
        }
      }
    }
  }

  /**
   * OAuth2 / OpenID Connect redirect callback handler.
   * Example callback URL: /callback?code=...&state=...&error=...
   * Currently this extracts common params and logs them, then returns 200 OK.
   */
  private def createCallbackRoute(): Route = {
    path("callback") {
      get {
        parameterMap { params =>
          val code = params.get("code")
          val state = params.get("state")
          val error = params.get("error")
          val errorDescription = params.get("error_description")
          val redirectUri = params.get("redirect_uri")

          logger.info(s"OAuth callback received - code=${code.orNull}, state=${state.orNull}, error=${error.orNull}, error_description=${errorDescription.orNull}, redirect_uri=${redirectUri.orNull}")
          logger.debug(s"Full callback params: $params")

          complete(HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Callback received - check logs")
          ))
        }
      }
    }
  }

  /**
   * Create a Pekko HTTP route that uses HeaderBasedRouter to route to different handlers.
   * Requests are authenticated before being forwarded to MCP actors.
   */
  private def createMcpRoute(
    mcpEndpoint: String
  ): Route = {
    path(mcpEndpoint.stripPrefix("/")) {
      extractRequest { pekkoRequest =>
        // Validate authentication first
        ServerAuthService.authenticate(pekkoRequest.headers) match {
          case Left(MissingAuthHeader) =>
            logger.warn("Request rejected: missing authorization header")
            complete(HttpResponse(
              status = StatusCodes.Unauthorized,
              entity = HttpEntity(
                ContentTypes.`application/json`,
                """{"error": "unauthorized", "message": "Missing authorization header"}"""
              )
            ))
          case Left(AccessDenied) =>
            logger.warn("Request rejected: invalid or expired token")
            complete(HttpResponse(
              status = StatusCodes.Forbidden,
              entity = HttpEntity(
                ContentTypes.`application/json`,
                """{"error": "forbidden", "message": "Invalid or expired token"}"""
              )
            ))
          case Right(authContext) =>
            logger.debug(s"Request authenticated for user: ${authContext.userId}")
            // Chain the lookup and the actor ask using flatMap/map so we can use map on Futures
            val responseFuture = for {
              routeResult <- sessionMap.lookup(pekkoRequest)
              // derived values can be bound inside the for comprehension
              mcpActorId = routeResult.mcpActorId

              askResult <- pekkoRequest.method.value match {
                case "GET" =>
                  ActorLookup.getMcpServerActor(mcpActorId)
                    .ask[Either[McpServerActor.McpActorError, HttpResponse]](replyTo => {
                      logger.debug(s"Routing GET request to MCP actor: $mcpActorId for user: ${authContext.userId}")
                      McpServerActor.McpGetRequest(pekkoRequest, replyTo)
                    })
                case "POST" =>
                  ActorLookup.getMcpServerActor(mcpActorId)
                    .ask[Either[McpServerActor.McpActorError, HttpResponse]](replyTo => {
                      logger.debug(s"Routing POST request to MCP actor: $mcpActorId for user: ${authContext.userId}")
                      McpServerActor.McpPostRequest(pekkoRequest, replyTo)
                    })
                case "DELETE" =>
                  ActorLookup.getMcpServerActor(mcpActorId)
                    .ask[Either[McpServerActor.McpActorError, HttpResponse]](replyTo => {
                      logger.debug(s"Routing DELETE request to MCP actor: $mcpActorId for user: ${authContext.userId}")
                      McpServerActor.McpDeleteRequest(pekkoRequest, replyTo)
                    })
                case other =>
                  logger.warn(s"Method $other not supported by MCP gateway")
                  scala.concurrent.Future.successful(Left(McpServerActor.ProcessingError(s"Method $other not supported")))
              }
            } yield {
              askResult match {
                case right@Right(httpResponse) =>
                  val sessionIdOpt = httpResponse.headers.find(_.name.toLowerCase == "mcp-session-id").map(_.value)
                  logger.debug(s"Received response from MCP actor: $mcpActorId")
                  logger.debug(s"Updating session mapping: ${sessionIdOpt.orNull} -> $mcpActorId")
                  sessionMap.updateSessionMapping(sessionIdOpt.getOrElse(""), mcpActorId)
                  right
                case left@Left(_) => left
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


}

object TurnstileMcpGateway {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpGateway])

  def apply(
    mcpStreamingConfig: Config,
    authConfig: Config,
  )(
    implicit system: ActorSystem[?]
  ): TurnstileMcpGateway = new TurnstileMcpGateway(mcpStreamingConfig, authConfig)
}