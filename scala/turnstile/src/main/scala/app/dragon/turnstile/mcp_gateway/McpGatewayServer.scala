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

package app.dragon.turnstile.mcp_gateway

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


class McpGatewayServer(
  mcpConfig: Config,
  authConfig: Config,
  db: Database
)(
  implicit system: ActorSystem[?]
) {
  implicit val timeout: Timeout = Timeout(10.seconds)
  implicit val sharding: ClusterSharding = ClusterSharding(system)
  implicit val database: Database = db

  private val logger: Logger = LoggerFactory.getLogger(classOf[McpGatewayServer])

  // Execution context for async operations
  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val sessionMap: SessionMap = new SessionMap()

  val serverVersion: String = mcpConfig.getString("server-version")
  val host: String = mcpConfig.getString("host")
  val port: Int = mcpConfig.getInt("port")
  val mcpEndpoint: String = mcpConfig.getString("mcp-endpoint")
  val auth0Domain: String = authConfig.getString("server.domain")

  // Create the service implementation
  private val serviceImpl = new McpGatewayServiceImpl(
    auth0Domain = auth0Domain,
    mcpEndpoint = mcpEndpoint,
    sessionMap = sessionMap
  )(system, timeout, sharding, database)

  def start(): Unit = {
    // Create actor system for Pekko HTTP
    try {
      logger.info(s"✓ Configured header-based router (dynamic handler creation)")
      logger.info(s"  Routing header: mcp-session-id")
      logger.info(s"  Fallback enabled: true")

      // Create Pekko HTTP route using the service implementation
      val route = serviceImpl.createRoutes()

      // Start the Pekko HTTP server
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
}

object McpGatewayServer {
  private val logger: Logger = LoggerFactory.getLogger(classOf[McpGatewayServer])

  def apply(
    mcpStreamingConfig: Config,
    authConfig: Config,
    db: Database
  )(
    implicit system: ActorSystem[?]
  ): McpGatewayServer = new McpGatewayServer(mcpStreamingConfig, authConfig, db)
}