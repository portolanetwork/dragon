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
import app.dragon.turnstile.auth.ServerAuthService.{AccessDenied, MissingAuthHeader}
import app.dragon.turnstile.auth.{ClientAuthService, ServerAuthService}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import pdi.jwt.JwtClaim
import slick.jdbc.JdbcBackend.Database

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
  authConfig: Config,
  db: Database
)(
  implicit system: ActorSystem[?]
) {
  implicit val timeout: Timeout = Timeout(10.seconds)
  implicit val sharding: ClusterSharding = ClusterSharding(system)
  implicit val database: Database = db


  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpGateway])

  // Execution context for async operations
  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val sessionMap: SessionMap = new SessionMap()

  val serverVersion: String = mcpConfig.getString("server-version")
  val host: String = mcpConfig.getString("host")
  val port: Int = mcpConfig.getInt("port")
  val mcpEndpoint: String = mcpConfig.getString("mcp-endpoint")
  val auth0Domain: String = authConfig.getString("server.domain")

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
        createWellKnownRoute() ~ createCallbackRoute() ~ createLoginRoute() ~ createMcpRoute(mcpEndpoint)

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
   * Exchanges the authorization code for tokens and displays them in logs.
   */
  private def createCallbackRoute(): Route = {
    path("callback") {
      get {
        parameterMap { params =>
          val codeOpt = params.get("code")
          val stateOpt = params.get("state")
          val errorOpt = params.get("error")
          val errorDescriptionOpt = params.get("error_description")

          logger.info(s"OAuth callback received - code=${codeOpt.orNull}, state=${stateOpt.orNull}, error=${errorOpt.orNull}, error_description=${errorDescriptionOpt.orNull}")

          // Check for errors first
          errorOpt match {
            case Some(error) =>
              val description = errorDescriptionOpt.getOrElse("No description provided")
              logger.error(s"OAuth error: $error - $description")
              complete(HttpResponse(
                status = StatusCodes.BadRequest,
                entity = HttpEntity(
                  ContentTypes.`text/html(UTF-8)`,
                  s"""<html><body><h1>OAuth Error</h1><p>Error: $error</p><p>Description: $description</p></body></html>"""
                )
              ))

            case None =>
              // Proceed with token exchange
              (codeOpt, stateOpt) match {
                case (Some(code), Some(state)) =>
                  val responseFuture = ClientAuthService.exchangeAuthCode(code, state).map {
                    case Right(token) =>
                      logger.info("================================================================================")
                      logger.info("🎉 OAuth Token Exchange Successful!")
                      logger.info("================================================================================")
                      logger.info(s"Access Token: ${token.accessToken.take(50)}...")
                      logger.info(s"Refresh Token: ${token.refreshToken.take(50)}...")
                      token.expiresIn.foreach { exp =>
                        logger.info(s"Expires In: $exp seconds")
                      }
                      logger.info("================================================================================")


                      HttpResponse(
                        status = StatusCodes.OK,
                        entity = HttpEntity(
                          ContentTypes.`text/html(UTF-8)`,
                          s"""
                            |<html>
                            |<body>
                            |  <h1>Authentication Successful!</h1>
                            |  <p>Your MCP server has been authenticated.</p>
                            |  <p>Access token: ${token.accessToken.take(20)}...</p>
                            | <p>Refresh token: ${token.refreshToken.take(20)}...</p>
                            |  <p>Check server logs for full token details.</p>
                            |</body>
                            |</html>
                          """.stripMargin
                        )
                      )

                    case Left(authError) =>
                      logger.error(s"Token exchange failed: ${authError.message}")
                      HttpResponse(
                        status = StatusCodes.InternalServerError,
                        entity = HttpEntity(
                          ContentTypes.`text/html(UTF-8)`,
                          s"""
                            |<html>
                            |<body>
                            |  <h1>Authentication Failed</h1>
                            |  <p>Error: ${authError.message}</p>
                            |  <p>Check server logs for more details.</p>
                            |</body>
                            |</html>
                          """.stripMargin
                        )
                      )
                  }

                  complete(responseFuture)

                case _ =>
                  logger.warn("OAuth callback missing required parameters (code or state)")
                  complete(HttpResponse(
                    status = StatusCodes.BadRequest,
                    entity = HttpEntity(
                      ContentTypes.`text/html(UTF-8)`,
                      """<html><body><h1>Bad Request</h1><p>Missing required parameters: code or state</p></body></html>"""
                    )
                  ))
              }
          }
        }
      }
    }
  }

  /**
   * Login route to initiate OAuth flow for an MCP server.
   * Accepts a UUID parameter to lookup the MCP server in the database.
   * Initiates OAuth flow and stores the resulting tokens.
   *
   * Example: GET /login?uuid=550e8400-e29b-41d4-a716-446655440000
   */
  private def createLoginRoute(): Route = {
    path("login") {
      get {
        parameter("uuid") { uuidStr =>
          extractRequest { pekkoRequest =>
            // Parse UUID
            val uuidEither = scala.util.Try(java.util.UUID.fromString(uuidStr)).toEither
              .left.map(_ => "Invalid UUID format")

            uuidEither match {
              case Left(error) =>
                logger.warn(s"Login request rejected: $error")
                complete(HttpResponse(
                  status = StatusCodes.BadRequest,
                  entity = HttpEntity(
                    ContentTypes.`application/json`,
                    s"""{"error": "invalid_uuid", "message": "$error"}"""
                  )
                ))
              case Right(uuid) =>
                // Validate authentication
                //ServerAuthService.authenticate(pekkoRequest.headers) match {
                Right(ServerAuthService.AuthContext("", "", JwtClaim())) match {
                  /*
                  case Left(MissingAuthHeader) =>
                    logger.warn("Login request rejected: missing authorization header")
                    complete(HttpResponse(
                      status = StatusCodes.Unauthorized,
                      entity = HttpEntity(
                        ContentTypes.`application/json`,
                        """{"error": "unauthorized", "message": "Missing authorization header"}"""
                      )
                    ))
                  case Left(AccessDenied) =>
                    logger.warn("Login request rejected: invalid or expired token")
                    complete(HttpResponse(
                      status = StatusCodes.Forbidden,
                      entity = HttpEntity(
                        ContentTypes.`application/json`,
                        """{"error": "forbidden", "message": "Invalid or expired token"}"""
                      )
                    ))
                   */
                  case Right(authContext) =>
                    logger.info(s"Login request for UUID: $uuid from user: ${authContext.userId}")

                    // Initiate the auth code flow
                    val responseFuture = ClientAuthService.initiateAuthCodeFlow(uuid.toString).map {
                      case Right(loginUrl) =>
                        logger.info(s"OAuth flow initiated successfully, redirecting to: $loginUrl")
                        HttpResponse(
                          status = StatusCodes.Found,
                          headers = headers.Location(loginUrl) :: Nil
                        )
                      case Left(authError) =>
                        logger.error(s"Failed to initiate OAuth flow: ${authError.message}")
                        HttpResponse(
                          status = StatusCodes.InternalServerError,
                          entity = HttpEntity(
                            ContentTypes.`application/json`,
                            s"""{"error": "auth_flow_failed", "message": "${authError.message}"}"""
                          )
                        )
                    }

                    complete(responseFuture)
                }
            }
          }
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
    db: Database
  )(
    implicit system: ActorSystem[?]
  ): TurnstileMcpGateway = new TurnstileMcpGateway(mcpStreamingConfig, authConfig, db)
}