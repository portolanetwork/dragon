package app.dragon.turnstile.controller

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.mcp.TurnstileMcpServer
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.slf4j.{Logger, LoggerFactory}

object Guardian {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  val grpcConfig: Config = ApplicationConfig.grpcConfig
  val mcpConfig: Config = ApplicationConfig.rootConfig.getConfig("turnstile.mcp")

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

      // Start MCP server if enabled
      if (mcpConfig.getBoolean("enabled")) {
        try {
          val mcpServer = TurnstileMcpServer(mcpConfig)
          mcpServer.start()
          context.log.info("MCP Server started successfully")
        } catch {
          case e: Exception =>
            context.log.error("Failed to start MCP Server", e)
        }
      } else {
        context.log.info("MCP Server is disabled")
      }

      Behaviors.empty
    }
}
