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

package app.dragon.turnstile.mgmt

import app.dragon.turnstile.config.ApplicationConfig
import dragon.turnstile.api.v1.{TurnstileService, TurnstileServiceHandler, TurnstileServicePowerApiHandler}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.grpc.scaladsl.{ServerReflection, ServiceHandler, WebHandler}
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.*
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpMethods.*
import org.apache.pekko.http.scaladsl.model.headers.HttpOrigin
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * gRPC-Web server hosting TurnstileService with browser compatibility.
 *
 * This server wraps the standard gRPC services with grpc-web protocol support,
 * enabling direct browser access without requiring a proxy. It includes CORS
 * configuration for cross-origin requests and supports both grpc-web and grpc-web-text formats.
 *
 * Key differences from MgmtGrpcServer:
 * - Uses WebHandler.grpcWebHandler() for grpc-web protocol support
 * - Includes CORS configuration for browser requests
 * - Supports both application/grpc-web and application/grpc-web-text content types
 * - Typically runs on a different port than standard gRPC (e.g., 8081 vs 8080)
 */
object MgmtGrpcWebServer {
  val logger: Logger = LoggerFactory.getLogger(classOf[MgmtGrpcWebServer])

  def start(host: String, port: Int, system: ActorSystem[Nothing]): Future[Http.ServerBinding] = {
    logger.info("Starting TurnstileWebServer...")
    new MgmtGrpcWebServer(system).run(host, port)
  }
}

class MgmtGrpcWebServer private(
  system: ActorSystem[Nothing]
) {
  import MgmtGrpcWebServer.logger

  def run(host: String, port: Int): Future[Http.ServerBinding] = {
    implicit val sys = system
    implicit val ec: ExecutionContext = system.executionContext

    // Create database instance
    implicit val db: Database = Database.forConfig("", ApplicationConfig.db)

    // Initialize cluster sharding
    implicit val sharding: ClusterSharding = ClusterSharding(system)

    // Create service handlers
    val turnstileServiceHandler = TurnstileServicePowerApiHandler.partial(
      MgmtServiceImpl(ApplicationConfig.serverAuthEnabled)
    )

    // Create server reflection for both services
    val reflection = ServerReflection.partial(List(TurnstileService))

    // Combine all service handlers
    val serviceHandlers = ServiceHandler.concatOrNotFound(
      turnstileServiceHandler,
      reflection
    )

    // Configure CORS for browser requests
    val corsSettings = CorsSettings.defaultSettings
      .withAllowedOrigins(HttpOriginMatcher.*)
      .withAllowedMethods(Seq(GET, POST, PUT, DELETE, OPTIONS, HEAD))
      .withAllowGenericHttpRequests(true)
      .withAllowCredentials(true)
      .withExposedHeaders(Seq(
        "grpc-status",
        "grpc-message",
        "grpc-status-details-bin",
        "x-grpc-web",
        "x-user-agent"
      ))

    // Convert service handler function to PartialFunction for grpc-web compatibility
    val serviceHandlersPartial: PartialFunction[org.apache.pekko.http.scaladsl.model.HttpRequest,
      Future[org.apache.pekko.http.scaladsl.model.HttpResponse]] = {
      case req => serviceHandlers(req)
    }

    // Wrap with grpc-web handler for browser compatibility
    // WebHandler.grpcWebHandler enables grpc-web protocol support for browser clients
    val grpcWebHandler = WebHandler.grpcWebHandler(serviceHandlersPartial)

    // Create route with CORS support
    // The handler is wrapped in a route directive to integrate with CORS
    val route: Route = cors(corsSettings) {
      extractRequest { request =>
        complete(grpcWebHandler(request))
      }
    }

    // Bind the server
    val bound: Future[Http.ServerBinding] = Http()
      .newServerAt(host, port)
      .bind(route)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))

    bound.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        logger.info("gRPC-Web server bound to {}:{}", address.getHostString, address.getPort)
        logger.info("  - TurnstileService available via grpc-web")
        logger.info("  - Server reflection enabled")
        logger.info("  - CORS enabled for browser access")
        logger.info("  - Supports: application/grpc-web, application/grpc-web-text")
      case Failure(ex) =>
        logger.error("Failed to bind gRPC-Web endpoint, terminating system", ex)
        system.terminate()
    }

    bound
  }
}
