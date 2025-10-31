package app.dragon.turnstile.client

import app.dragon.turnstile.config.ApplicationConfig
import io.modelcontextprotocol.client.{McpAsyncClient, McpClient}
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

object TurnstileStreamingHttpAsyncMcpClient {
  private val logger = LoggerFactory.getLogger(classOf[TurnstileStreamingHttpAsyncMcpClient])

  val clientName: String = "Streaming client" //ApplicationConfig.mcpStreaming.getString("client-name")
  val clientVersion: String = "0.1.0" //ApplicationConfig.mcpStreaming.getString("client-version")

  def apply(serverUrl: String, endpoint: String = "/mcp")(implicit ec: ExecutionContext): TurnstileStreamingHttpAsyncMcpClient =
    new TurnstileStreamingHttpAsyncMcpClient(clientName, clientVersion, serverUrl, endpoint).start()
}

class TurnstileStreamingHttpAsyncMcpClient(
  val clientName: String,
  val clientVersion: String,
  val serverUrl: String,
  val endpoint: String
)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(classOf[TurnstileStreamingHttpAsyncMcpClient])

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
  private def start(): TurnstileStreamingHttpAsyncMcpClient = {
    logger.info(s"Creating MCP async client: $clientName v$clientVersion")
    logger.info(s"Target server: $serverUrl$endpoint")

    // Create the transport
    val transport = HttpClientStreamableHttpTransport.builder(serverUrl)
      .endpoint(endpoint)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .resumableStreams(true)
      .build()

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
  def callTool(request: McpSchema.CallToolRequest): Future[McpSchema.CallToolResult] = {
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
  def readResource(uri: String): Future[McpSchema.ReadResourceResult] = {
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
  def getPrompt(request: McpSchema.GetPromptRequest): Future[McpSchema.GetPromptResult] = {
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
  private def formatTimestamp(millis: Long): String = {
    val instant = java.time.Instant.ofEpochMilli(millis)
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("HH:mm:ss.SSS")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)
  }

  def getMcpAsyncClient: McpAsyncClient = mcpAsyncClient

  def getIsInitialized: Boolean = isInitialized
}
