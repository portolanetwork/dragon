package app.dragon.turnstile.controller

import com.example.helloworld.{GreeterService, GreeterServiceHandler}
import app.dragon.turnstile.GreeterServiceImpl
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.scaladsl.{ServerReflection, ServiceHandler}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object GreeterServer {
  val logger: Logger = LoggerFactory.getLogger(classOf[GreeterServer])

  def start(host: String, port: Int, system: ActorSystem[Nothing]): Future[Http.ServerBinding] = {
    logger.info("Starting GreeterServer...")
    new GreeterServer(system).run(host, port)
  }
}

class GreeterServer private(system: ActorSystem[Nothing]) {
  import GreeterServer.logger

  def run(host: String, port: Int): Future[Http.ServerBinding] = {
    implicit val sys = system
    implicit val ec: ExecutionContext = system.executionContext

    val greeterServiceHandler = GreeterServiceHandler.partial(new GreeterServiceImpl(system))
    val reflection = ServerReflection.partial(List(GreeterService))

    val serviceHandlers = ServiceHandler.concatOrNotFound(greeterServiceHandler, reflection)

    val bound: Future[Http.ServerBinding] = Http()(system)
      .newServerAt(host, port)
      .bind(serviceHandlers)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))

    bound.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        logger.info("gRPC server bound to {}:{}", address.getHostString, address.getPort)
      case Failure(ex) =>
        logger.error("Failed to bind gRPC endpoint, terminating system", ex)
        system.terminate()
    }

    bound
  }
}
