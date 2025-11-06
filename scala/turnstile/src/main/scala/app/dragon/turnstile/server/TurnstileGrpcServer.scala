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

package app.dragon.turnstile.server

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.service.TurnstileServiceImpl
import dragon.turnstile.api.v1.{TurnstileService, TurnstileServiceHandler, TurnstileServicePowerApiHandler}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.scaladsl.{ServerReflection, ServiceHandler}
import org.apache.pekko.http.scaladsl.Http
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Combined gRPC server hosting both GreeterService and TurnstileService.
 *
 * This server binds multiple gRPC services to a single port and provides
 * server reflection for both services.
 */
object TurnstileGrpcServer {
  val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileGrpcServer])

  def start(host: String, port: Int, system: ActorSystem[Nothing]): Future[Http.ServerBinding] = {
    logger.info("Starting TurnstileServer...")
    new TurnstileGrpcServer(system).run(host, port)
  }
}

class TurnstileGrpcServer private(
  system: ActorSystem[Nothing]
) {
  import TurnstileGrpcServer.logger

  def run(host: String, port: Int): Future[Http.ServerBinding] = {
    implicit val sys = system
    implicit val ec: ExecutionContext = system.executionContext

    // Create database instance
    implicit val db: Database = Database.forConfig("", ApplicationConfig.dbConfig)

    // Create service handlers
    val turnstileServiceHandler = TurnstileServicePowerApiHandler.partial(TurnstileServiceImpl())

    // Create server reflection for both services
    val reflection = ServerReflection.partial(List(TurnstileService))

    // Combine all service handlers
    val serviceHandlers = ServiceHandler.concatOrNotFound(
      turnstileServiceHandler,
      reflection
    )

    // Bind the server
    val bound: Future[Http.ServerBinding] = Http()
      .newServerAt(host, port)
      .bind(serviceHandlers)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))

    bound.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        logger.info("gRPC server bound to {}:{}", address.getHostString, address.getPort)
        logger.info("  - GreeterService available")
        logger.info("  - TurnstileService available")
        logger.info("  - Server reflection enabled")
      case Failure(ex) =>
        logger.error("Failed to bind gRPC endpoint, terminating system", ex)
        system.terminate()
    }

    bound
  }
}
