package app.dragon.turnstile.controller

import app.dragon.turnstile.service.TurnstileServiceImpl
import app.dragon.turnstile.config.ApplicationConfig
import dragon.turnstile.api.v1.{TurnstileService, TurnstileServiceHandler}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.scaladsl.{ServerReflection, ServiceHandler}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
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
object TurnstileServer {
  val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileServer])

  def start(host: String, port: Int, system: ActorSystem[Nothing]): Future[Http.ServerBinding] = {
    logger.info("Starting TurnstileServer...")
    new TurnstileServer(system).run(host, port)
  }
}

class TurnstileServer private(system: ActorSystem[Nothing]) {
  import TurnstileServer.logger

  def run(host: String, port: Int): Future[Http.ServerBinding] = {
    implicit val sys = system
    implicit val ec: ExecutionContext = system.executionContext

    // Create database instance
    implicit val db: Database = Database.forConfig("turnstile.database.db", ApplicationConfig.rootConfig)

    // Create service implementations
    val turnstileServiceImpl = new TurnstileServiceImpl(system)

    // Create service handlers
    val turnstileServiceHandler = TurnstileServiceHandler.partial(turnstileServiceImpl)

    // Create server reflection for both services
    val reflection = ServerReflection.partial(List(TurnstileService))

    // Combine all service handlers
    val serviceHandlers = ServiceHandler.concatOrNotFound(
      turnstileServiceHandler,
      reflection
    )

    // Bind the server
    val bound: Future[Http.ServerBinding] = Http()(system)
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
