package app.dragon.turnstile.controller

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.db.DatabaseMigration
import app.dragon.turnstile.mcp.{StdioMcpServer, StreamingHttpMcpServer}
import app.dragon.turnstile.service.ToolsService
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.slf4j.{Logger, LoggerFactory}

object Guardian {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  val grpcConfig: Config = ApplicationConfig.grpcConfig
  val mcpStdioConfig: Config = ApplicationConfig.rootConfig.getConfig("turnstile.mcp-stdio")
  val mcpStreamingConfig: Config = ApplicationConfig.rootConfig.getConfig("turnstile.mcp-streaming")
  val databaseConfig: Config = ApplicationConfig.rootConfig.getConfig("turnstile.database.db")

  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val system: ActorSystem[?] = context.system

      context.log.info("Starting Guardian...")

      // Run database migrations first
      context.log.info("Running database migrations...")
      DatabaseMigration.migrate(ApplicationConfig.rootConfig) match {
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
      // Example:
      // MyActor.initSharding(context.system)

      // Start GRPC server (hosting both GreeterService and TurnstileService)
      TurnstileServer.start(grpcHost, grpcPort, context.system)
      
      // Start MCP Stdio server if enabled
      if (mcpStdioConfig.getBoolean("enabled")) {
        try {
          val mcpStdioServer = StdioMcpServer(mcpStdioConfig)
          mcpStdioServer.start()
          context.log.info("MCP Stdio Server started successfully")
        } catch {
          case e: Exception =>
            context.log.error("Failed to start MCP Stdio Server", e)
        }
      } else {
        context.log.info("MCP Stdio Server is disabled")
      }

      // Start MCP Streaming HTTP server if enabled
      if (mcpStreamingConfig.getBoolean("enabled")) {
        try {
          val mcpStreamingServer = StreamingHttpMcpServer(mcpStreamingConfig)
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
