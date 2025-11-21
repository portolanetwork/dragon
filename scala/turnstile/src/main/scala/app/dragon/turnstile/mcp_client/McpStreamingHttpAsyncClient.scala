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

package app.dragon.turnstile.mcp_client

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer
import io.modelcontextprotocol.client.{McpAsyncClient, McpClient}
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

/**
 * Factory for creating MCP client instances.
 */
object McpStreamingHttpAsyncClient {
  private val logger = LoggerFactory.getLogger(classOf[McpStreamingHttpAsyncClient])

  val clientName: String = "Streaming client"
  val clientVersion: String = "0.1.0"

  /**
   * Create a new MCP client connected to a downstream server.
   *
   * @param serverUrl The base URL of the MCP server (e.g., "http://localhost:8080")
   * @param endpoint The MCP endpoint path (default: "/mcp")
   * @param authTokenProvider Optional function to get access token for authentication
   * @param ec Execution context for async operations
   * @return A started TurnstileStreamingHttpAsyncMcpClient instance
   */
  def apply(
    serverUrl: String,
    //endpoint: String,
    authTokenProvider: Option[() => Future[String]] = None
  )(implicit ec: ExecutionContext): McpStreamingHttpAsyncClient = {
    // Parse serverUrl into baseUrl and endpoint
    // Expected format: "http://localhost:8080/mcp" or "http://localhost:8080"
    val url = new java.net.URL(serverUrl)
    val baseUrl = s"${url.getProtocol}://${url.getAuthority}"
    val endpoint = url.getPath match {
      case "" | "/" => "/"
      case path => path
    }
  
    new McpStreamingHttpAsyncClient(clientName, clientVersion, baseUrl, endpoint, authTokenProvider).start()
  }
}

/**
 * Async MCP client for connecting to downstream MCP servers.
 *
 * This client wraps the MCP Java SDK's McpAsyncClient and provides a Scala-friendly
 * Future-based API. It handles HTTP/SSE communication with downstream MCP servers
 * and exposes their tools, resources, and prompts to the Turnstile gateway.
 *
 * Architecture:
 * - Built on MCP Java SDK (io.modelcontextprotocol.client.McpAsyncClient)
 * - Uses HTTP streaming transport with Server-Sent Events (SSE)
 * - Embedded in McpClientActor for actor isolation
 * - Bridges Reactor Mono ↔ Scala Future
 *
 * MCP Protocol Support:
 * - Tools: Call tools, list available tools
 * - Resources: Read resources, list available resources
 * - Prompts: Get prompts, list available prompts
 * - Notifications: Tools changed, resources changed, prompts changed, logging, progress
 * - Health: Ping/pong for liveness checks
 *
 * Lifecycle:
 * 1. start(): Create McpAsyncClient with transport and notification handlers
 * 2. initialize(): Perform MCP handshake, exchange capabilities
 * 3. Active: Handle requests and notifications
 * 4. closeGracefully(): Send close message and wait for acknowledgment
 * 5. close(): Immediate close without waiting
 *
 * Notification Handling:
 * - Tools Changed: Logs tool list updates
 * - Resources Changed: Logs resource list updates
 * - Prompts Changed: Logs prompt list updates
 * - Logging: Receives and logs server messages
 * - Progress: Tracks long-running operations with correlation metadata
 *
 * Progress Notifications:
 * Progress updates include correlation metadata (toolName, iteration, elapsedSeconds)
 * to track streaming tool execution across multiple iterations.
 *
 * Authentication:
 * - Supports OAuth token injection via authTokenProvider
 * - Tokens are dynamically fetched for each HTTP request
 * - Uses asyncHttpRequestCustomizer to inject Authorization header
 *
 * Usage:
 * {{{
 * val client = TurnstileStreamingHttpAsyncMcpClient("http://localhost:8080")
 * client.initialize().map { result =>
 *   println(s"Connected to ${result.serverInfo().name()}")
 * }
 *
 * // List tools
 * client.listTools().map { result =>
 *   result.tools().forEach(tool => println(tool.name()))
 * }
 *
 * // Call a tool
 * val request = McpSchema.CallToolRequest.builder()
 *   .name("example_tool")
 *   .build()
 * client.callTool(request).map { result =>
 *   println(result.content())
 * }
 * }}}
 *
 * @param clientName The MCP client name
 * @param clientVersion The MCP client version
 * @param serverUrl The downstream server base URL
 * @param endpoint The MCP endpoint path
 * @param authTokenProvider Optional function to get access token for authentication
 * @param ec Execution context for async operations
 */
class McpStreamingHttpAsyncClient(
  val clientName: String,
  val clientVersion: String,
  val serverUrl: String,
  val endpoint: String,
  val authTokenProvider: Option[() => Future[String]]
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(classOf[McpStreamingHttpAsyncClient])

  // Set in start()
  private var mcpAsyncClient: McpAsyncClient = null
  private var isInitialized: Boolean = false

  /**
   * Convert Reactor Mono to Scala Future.
   * This bridges the gap between the reactive Java API and Scala's Future-based API.
   */
  private def monoToFuture[T](mono: Mono[T]): Future[T] = {
    val promise = Promise[T]()
    mono.subscribe(
      value => promise.success(value),
      error => promise.failure(error)
    )
    promise.future
  }

  /**
   * Create and initialize the MCP async client.
   *
   * @return this instance for chaining
   */
  private def start(): McpStreamingHttpAsyncClient = {
    logger.info(s"Creating MCP async client: $clientName v$clientVersion")
    logger.info(s"Target server: $serverUrl$endpoint")

    // Create the transport with optional auth customizer
    val transportBuilder = HttpClientStreamableHttpTransport.builder(serverUrl)
      .endpoint(endpoint)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .resumableStreams(true)

    // Add auth header customizer if token provider is available
    val transportWithAuth = authTokenProvider match {
      case Some(tokenProvider) =>
        logger.info("Configuring authentication header injection")

        transportBuilder.asyncHttpRequestCustomizer(
          (builder, method, uri, body, context) => {
            // Fetch token asynchronously and inject Authorization header
            Mono.fromFuture(
              tokenProvider().asJava.toCompletableFuture
            ).map(token => {
              logger.debug(s"Injecting Authorization header for $method $uri")
              builder.header("Authorization", s"Bearer $token")
              builder
            }).onErrorResume(error => {
              logger.error(s"Failed to fetch auth token: ${error.getMessage}", error)
              // Return builder without auth header on error
              Mono.just(builder)
            })
          }
        )
      case None =>
        logger.info("No authentication configured")
        transportBuilder
    }

    val transport = transportWithAuth.build()

    // Build the async client with handlers
    mcpAsyncClient = McpClient.async(transport)
      .requestTimeout(java.time.Duration.ofSeconds(30))
      .initializationTimeout(java.time.Duration.ofSeconds(15))
      .clientInfo(new McpSchema.Implementation(clientName, clientVersion))
      .toolsChangeConsumer(tools =>
        Mono.fromRunnable(() =>
          logger.info(s"[NOTIFICATION] Tools changed: ${tools.asScala.map(_.name()).mkString(", ")}")
        )
      )
      .resourcesChangeConsumer(resources =>
        Mono.fromRunnable(() =>
          logger.info(s"[NOTIFICATION] Resources changed: ${resources.asScala.map(_.uri()).mkString(", ")}")
        )
      )
      .promptsChangeConsumer(prompts =>
        Mono.fromRunnable(() =>
          logger.info(s"[NOTIFICATION] Prompts changed: ${prompts.asScala.map(_.name()).mkString(", ")}")
        )
      )
      .loggingConsumer(notification =>
        Mono.fromRunnable(() => {
          val level = notification.level()
          val data = notification.data()
          logger.info(s"[SERVER LOG - $level] $data")
        })
      )
      .progressConsumer(notification =>
        Mono.fromRunnable(() => {
          val timestamp = formatTimestamp(System.currentTimeMillis())
          val progress = notification.progress()
          val total = notification.total()
          val progressStr = if (total != null) s"$progress / $total" else s"$progress"
          val percentage = if (total != null && total.doubleValue() > 0) {
            f"${(progress.doubleValue() / total.doubleValue()) * 100}%.0f%%"
          } else {
            ""
          }
          val message = if (notification.message() != null) s" - ${notification.message()}" else ""

          // Extract correlation metadata if present
          val meta = notification.meta()
          val correlationInfo = if (meta != null && !meta.isEmpty) {
            val parts = List(
              Option(meta.get("toolName")).map(v => s"tool=$v"),
              Option(meta.get("iteration")).map(v => s"iter=$v"),
              Option(meta.get("elapsedSeconds"))
                .map(_.asInstanceOf[Double])
                .map(v => f"elapsed=$v%.1fs")
            ).flatten
            if (parts.nonEmpty) s" [${parts.mkString(", ")}]" else ""
          } else {
            ""
          }

          logger.info(s"[$timestamp] [PROGRESS] ${notification.progressToken()}: $progressStr $percentage$message$correlationInfo")
        })
      )
      .build()

    logger.info(s"✓ Created MCP async client: $clientName v$clientVersion")

    this
  }

  /**
   * Initialize the connection to the server.
   */
  def initialize(): Future[McpSchema.InitializeResult] = {
    monoToFuture(mcpAsyncClient.initialize()).map { result =>
      isInitialized = true
      logger.info(s"✓ Client initialized with server: ${result.serverInfo().name()} v${result.serverInfo().version()}")
      result
    }
  }

  /**
   * List available tools from the server.
   */
  def listTools(): Future[McpSchema.ListToolsResult] = {
    monoToFuture(mcpAsyncClient.listTools())
  }

  /**
   * Call a tool on the server.
   */
  def callTool(
    request: McpSchema.CallToolRequest
  ): Future[McpSchema.CallToolResult] = {
    monoToFuture(mcpAsyncClient.callTool(request))
  }

  /**
   * List available resources from the server.
   */
  def listResources(): Future[McpSchema.ListResourcesResult] = {
    monoToFuture(mcpAsyncClient.listResources())
  }

  /**
   * Read a resource from the server.
   */
  def readResource(
    uri: String
  ): Future[McpSchema.ReadResourceResult] = {
    monoToFuture(mcpAsyncClient.readResource(new McpSchema.ReadResourceRequest(uri)))
  }

  /**
   * List available prompts from the server.
   */
  def listPrompts(): Future[McpSchema.ListPromptsResult] = {
    monoToFuture(mcpAsyncClient.listPrompts())
  }

  /**
   * Get a prompt from the server.
   */
  def getPrompt(
    request: McpSchema.GetPromptRequest
  ): Future[McpSchema.GetPromptResult] = {
    monoToFuture(mcpAsyncClient.getPrompt(request))
  }

  /**
   * Ping the server.
   */
  def ping(): Future[Unit] = {
    monoToFuture(mcpAsyncClient.ping()).map(_ => ())
  }

  /**
   * Close the client gracefully.
   */
  def closeGracefully(): Future[Unit] = {
    monoToFuture(mcpAsyncClient.closeGracefully()).map(_ => ())
  }

  /**
   * Close the client immediately.
   */
  def close(): Unit = {
    mcpAsyncClient.close()
  }

  /**
   * Format timestamp for logging.
   */
  private def formatTimestamp(
    millis: Long
  ): String = {
    val instant = java.time.Instant.ofEpochMilli(millis)
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("HH:mm:ss.SSS")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)
  }

  def getMcpAsyncClient: McpAsyncClient = mcpAsyncClient

  def getIsInitialized: Boolean = isInitialized
}
