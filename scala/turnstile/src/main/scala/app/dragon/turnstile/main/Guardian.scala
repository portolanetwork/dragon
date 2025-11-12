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

package app.dragon.turnstile.main

import app.dragon.turnstile.actor.{AuthCodeFlowActor, McpClientActor, McpServerActor, McpSessionMapActor}
import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.db.DatabaseMigration
import app.dragon.turnstile.gateway.TurnstileMcpGateway
import app.dragon.turnstile.server.TurnstileGrpcServer
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.JdbcBackend.Database

/**
 * Guardian actor - the root supervisor for the Turnstile application.
 *
 * The Guardian implements the Guardian Pattern from Pekko's actor model, serving as the
 * top-level supervisor that initializes and monitors all critical application subsystems.
 *
 * Responsibilities:
 * 1. Database Migration - Runs Flyway migrations before starting services
 * 2. Actor Sharding Initialization - Sets up cluster sharding for:
 *    - McpServerActor: User-scoped MCP server instances
 *    - McpClientActor: Client connections to downstream MCP servers
 *    - McpSessionMapActor: Session management and routing
 * 3. gRPC Server - Starts the gRPC service for server registration/management
 * 4. MCP Gateway - Starts the HTTP-based MCP protocol gateway
 *
 * Initialization Sequence:
 * 1. Run database migrations (fail-fast if migration fails)
 * 2. Initialize cluster sharding for all actor types
 * 3. Start gRPC server for API access
 * 4. Start MCP Gateway if enabled in configuration
 *
 * Failure Handling:
 * - Database migration failure → terminates the system
 * - MCP Gateway startup failure → logs error but continues (gRPC still available)
 *
 * The Guardian uses Behaviors.empty as its final state since it doesn't process messages
 * after initialization - it simply supervises child actors.
 */
object Guardian {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  val grpcConfig: Config = ApplicationConfig.grpcConfig
  val mcpStreamingConfig: Config = ApplicationConfig.mcpStreaming
  val databaseConfig: Config = ApplicationConfig.db
  val authConfig: Config = ApplicationConfig.auth

  /**
   * Creates the Guardian actor behavior.
   *
   * @return Behavior[Nothing] - The Guardian doesn't process messages after setup
   */
  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val system: ActorSystem[?] = context.system

      context.log.info("Starting Guardian...")

      // Run database migrations first
      context.log.info("Running database migrations...")
      DatabaseMigration.migrate(ApplicationConfig.db) match {
        case scala.util.Success(result) =>
          context.log.info(s"Database migrations completed: ${result.migrationsExecuted} migrations executed")
          if (result.targetSchemaVersion != null) {
            context.log.info(s"Target schema version: ${result.targetSchemaVersion}")
          }

          val grpcHost = grpcConfig.getString("host")
          val grpcPort = grpcConfig.getInt("port")

          // Create database instance for MCP gateway
          implicit val db: Database = Database.forConfig("", ApplicationConfig.db)

          // Initialize sharding and actors here
          McpServerActor.initSharding(context.system)
          McpClientActor.initSharding(context.system, db)
          McpSessionMapActor.initSharding(context.system)
          AuthCodeFlowActor.initSharding(context.system)

          // Start GRPC server (hosting both GreeterService and TurnstileService)
          TurnstileGrpcServer.start(grpcHost, grpcPort, context.system)

          // Start MCP Streaming HTTP server if enabled
          if (mcpStreamingConfig.getBoolean("enabled")) {
            try {
              //val mcpStreamingServer = StreamingHttpMcpServer(mcpStreamingConfig)
              val mcpStreamingServer = TurnstileMcpGateway(mcpStreamingConfig, authConfig, db)

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

        case scala.util.Failure(ex) =>
          context.log.error("Database migration failed", ex)
          context.log.error("Application startup aborted due to migration failure")
          system.terminate()
          Behaviors.stopped
      }
    }
}
