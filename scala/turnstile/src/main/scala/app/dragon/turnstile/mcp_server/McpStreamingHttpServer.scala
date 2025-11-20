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

package app.dragon.turnstile.mcp_server

import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.mcp_tools.ToolsService
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider
import io.modelcontextprotocol.server.{McpAsyncServer, McpServer}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.web.reactive.function.server.RouterFunctions
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
 * Factory for creating user-scoped MCP server instances.
 */
object McpStreamingHttpServer {
  val logger: org.slf4j.Logger = LoggerFactory.getLogger(classOf[McpStreamingHttpServer])

  val serverName: String = ApplicationConfig.mcpStreaming.getString("server-name")
  val serverVersion: String = ApplicationConfig.mcpStreaming.getString("server-version")

  /**
   * Create a new MCP server instance for a specific user.
   *
   * @param userId The user identifier
   * @param system The actor system
   * @return A new TurnstileStreamingHttpMcpServer instance
   */
  def apply(userId: String)(implicit system: ActorSystem[?]): McpStreamingHttpServer =
    new McpStreamingHttpServer(serverName, serverVersion, userId)
}

/**
 * User-scoped MCP server implementation using Spring WebFlux transport.
 *
 * This class wraps the MCP Java SDK's McpAsyncServer and provides user-isolated
 * MCP server instances. Each user gets their own server with custom tool sets
 * combining default (built-in) tools and tools from registered downstream MCP servers.
 *
 * Architecture:
 * - Built on MCP Java SDK (io.modelcontextprotocol.server.McpAsyncServer)
 * - Uses Spring WebFlux for HTTP/SSE transport
 * - Embedded in McpServerActor for actor isolation
 * - User-scoped tool aggregation from multiple sources
 *
 * Tool Sources:
 * 1. Default Tools: Built-in tools (echo, system_info, etc.)
 * 2. Downstream Tools: Namespaced tools from registered MCP servers
 *
 * Lifecycle:
 * 1. start(): Create McpAsyncServer, register default tools
 * 2. refreshDownstreamTools(): Fetch and register namespaced tools from downstream servers
 * 3. Active: Handle MCP protocol requests via HttpHandler
 * 4. stop(): Clean shutdown of MCP server
 *
 * MCP Capabilities:
 * - Tools: ✓ (tool listing and execution)
 * - Resources: ✓ (with subscriptions)
 * - Prompts: ✓
 * - Logging: ✓
 * - Completions: ✓
 *
 * Usage:
 * {{{
 * val mcpServer = TurnstileStreamingHttpMcpServer(userId).start()
 * mcpServer.refreshDownstreamTools()  // Async tool registration
 *
 * // Get the HTTP handler for request processing
 * val httpHandler = mcpServer.getHttpHandler.get
 * httpHandler.handle(springRequest, springResponse)
 * }}}
 *
 * @param serverName The MCP server name
 * @param serverVersion The MCP server version
 * @param userId The user identifier for tool scoping
 * @param system The actor system (implicit)
 */
class McpStreamingHttpServer(
  val serverName: String,
  val serverVersion: String,
  val userId: String,
)(
  implicit val system: ActorSystem[?]
) {
  private val logger: org.slf4j.Logger = LoggerFactory.getLogger(classOf[McpStreamingHttpServer])
  // Execution context derived from the provided actor system
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val timeout: Timeout = 30.seconds

  // Provide an implicit Database for DbInterface calls
  private implicit val db: Database = Database.forConfig("", ApplicationConfig.db)

  // These are set in start()
  private var mcpAsyncServer: Option[McpAsyncServer] = None
  private var httpHandler: Option[HttpHandler] = None
  private var started: Boolean = false // Track if the server has been started

  /**
   * Create an MCP server instance with its HttpHandler.
   * Asynchronously fetches tools from both default sources and MCP client actors.
   *
   * @return Future[TurnstileStreamingHttpMcpServer] - completes when all tools are registered
   */
  def start(
  ): McpStreamingHttpServer = {
    if (started) {
      logger.warn(s"MCP server: $serverName v$serverVersion already started; ignoring duplicate start.")
      return this
    }
    started = true
    logger.info(s"Creating MCP server: $serverName v$serverVersion")

    val jsonMapper = McpJsonMapper.getDefault

    // Create WebFlux transport provider
    val transportProvider = WebFluxStreamableServerTransportProvider.builder()
      .jsonMapper(jsonMapper)
      .disallowDelete(false)
      .build()

    // Build MCP async server
    val mcpServer: McpAsyncServer = McpServer
      .async(transportProvider)
      .serverInfo(serverName, serverVersion)
      .capabilities(McpSchema.ServerCapabilities.builder()
        .resources(false, true)
        .tools(true)
        .prompts(true)
        .logging()
        .completions()
        .build())
      .build()
    
    val routerFunction = transportProvider.getRouterFunction
    val httpHandler = RouterFunctions.toHttpHandler(routerFunction)

    this.mcpAsyncServer = Some(mcpServer)
    this.httpHandler = Some(httpHandler)

    this
  }
  
  def addTool(
    toolSpec: AsyncToolSpecification
  ): Unit = {
    mcpAsyncServer.foreach { server =>
      logger.info(s"[$serverName] Adding tool: ${toolSpec.tool().name()}")
      server
        .addTool(toolSpec)
        .doOnSuccess(_ => logger.debug(s"[$serverName] Tool registered: ${toolSpec.tool().name()}"))
        .doOnError(ex => logger.error(s"[$serverName] Failed to register tool: ${toolSpec.tool().name()}", ex))
        .subscribe()
    }
  }

  def stop(): Unit = {
    mcpAsyncServer.foreach { server =>
      logger.info(s"Stopping MCP server: $serverName v$serverVersion")
      server.close()
      logger.info(s"✓ Stopped MCP server: $serverName v$serverVersion")
    }
  }

  def getMcpAsyncServer: Option[McpAsyncServer] = mcpAsyncServer

  def getHttpHandler: Option[HttpHandler] = httpHandler
}
