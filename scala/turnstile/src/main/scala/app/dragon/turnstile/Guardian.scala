package app.dragon.turnstile.controller

import com.typesafe.config.Config
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior, SupervisorStrategy}
import org.slf4j.{Logger, LoggerFactory}

object Guardian {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  val grpcConfig: Config = ApplicationConfig.grpcConfig

  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val system: ActorSystem[_] = context.system

      context.log.info("Starting Guardian...")

      val grpcHost = grpcConfig.getString("host")
      val grpcPort = grpcConfig.getInt("port")

      // Initialize sharding and actors here
      // Example:
      // MyActor.initSharding(context.system)

      // Start GRPC server
      GreeterServer.start(grpcHost, grpcPort, context.system)

      Behaviors.empty
    }
}
