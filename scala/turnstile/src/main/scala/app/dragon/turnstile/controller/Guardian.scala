package app.dragon.turnstile.controller

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.mcp.{StdioMcpServer, StreamingHttpMcpServer, TurnstileMcpServer}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.slf4j.{Logger, LoggerFactory}

object Guardian {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  val grpcConfig: Config = ApplicationConfig.grpcConfig
  val mcpConfig: Config = ApplicationConfig.rootConfig.getConfig("turnstile.mcp")
  val mcpStdioConfig: Config = ApplicationConfig.rootConfig.getConfig("turnstile.mcp-stdio")
  val mcpStreamingConfig: Config = ApplicationConfig.rootConfig.getConfig("turnstile.mcp-streaming")

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

      // Start MCP HTTP server if enabled
      if (mcpConfig.getBoolean("enabled")) {
        try {
          val mcpServer = TurnstileMcpServer(mcpConfig)
          mcpServer.start()
          context.log.info("MCP HTTP Server started successfully")
        } catch {
          case e: Exception =>
            context.log.error("Failed to start MCP HTTP Server", e)
        }
      } else {
        context.log.info("MCP HTTP Server is disabled")
      }

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
