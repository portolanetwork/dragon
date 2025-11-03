package app.dragon.turnstile.controller

import app.dragon.turnstile.actor.{McpClientActor, McpServerActor, McpSessionMapActor}
import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.db.DatabaseMigration
import app.dragon.turnstile.gateway.TurnstileMcpGateway
import app.dragon.turnstile.server.TurnstileGrpcServer
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.slf4j.{Logger, LoggerFactory}

object Guardian {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  val grpcConfig: Config = ApplicationConfig.grpcConfig
  val mcpStreamingConfig: Config = ApplicationConfig.mcpStreaming
  val databaseConfig: Config = ApplicationConfig.dbConfig

  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val system: ActorSystem[?] = context.system

      context.log.info("Starting Guardian...")

      // Run database migrations first
      context.log.info("Running database migrations...")
      DatabaseMigration.migrate(ApplicationConfig.dbConfig) match {
        case scala.util.Success(result) =>
          context.log.info(s"Database migrations completed: ${result.migrationsExecuted} migrations executed")
          if (result.targetSchemaVersion != null) {
            context.log.info(s"Target schema version: ${result.targetSchemaVersion}")
          }
        case scala.util.Failure(ex) =>
          context.log.error("Database migration failed", ex)
          context.log.error("Application startup aborted due to migration failure")
          system.terminate()
          return Behaviors.stopped
      }

      val grpcHost = grpcConfig.getString("host")
      val grpcPort = grpcConfig.getInt("port")

      // Initialize sharding and actors here
      McpServerActor.initSharding(context.system)
      McpClientActor.initSharding(context.system)
      McpSessionMapActor.initSharding(context.system)
      
      // Start GRPC server (hosting both GreeterService and TurnstileService)
      TurnstileGrpcServer.start(grpcHost, grpcPort, context.system)
      
      // Start MCP Streaming HTTP server if enabled
      if (mcpStreamingConfig.getBoolean("enabled")) {
        try {
          //val mcpStreamingServer = StreamingHttpMcpServer(mcpStreamingConfig)
          val mcpStreamingServer = TurnstileMcpGateway(mcpStreamingConfig)

          mcpStreamingServer.start()
          context.log.info("MCP Streaming HTTP Server started successfully")
        } catch {
          case e: Exception =>
            context.log.error("Failed to start MCP Streaming HTTP Server", e)
        }
      } else {
        context.log.info("MCP Streaming HTTP Server is disabled")
      }

      Behaviors.empty
    }
}
