package app.dragon.turnstile.client

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.{McpClientTransport, McpSchema}
import org.reactivestreams.Publisher
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.{Flux, Mono}

import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * Enhanced Scala wrapper for MCP Async Client using Streamable HTTP transport.
 *
 * This implementation uses the official MCP Java SDK's HttpClientStreamableHttpTransport
 * for bidirectional streaming communication over HTTP (not SSE).
 *
 * Key features:
 * - Pure streaming HTTP protocol (not Server-Sent Events)
 * - Custom request builders and async request customizers
 * - Reactive change notifications (tools, resources, prompts)
 * - Logging and progress consumers
 * - Sampling and elicitation support
 * - Context-aware requests using Reactor's context
 * - Scala Future-based API wrapping Reactor Mono/Flux
 *
 * Example usage:
 * {{{
 * import scala.concurrent.ExecutionContext.Implicits.global
 *
 * val client = StreamableHttpMcpAsyncClient.builder()
 *   .serverUrl("http://localhost:8082/mcp")
 *   .clientInfo("MyApp", "1.0.0")
 *   .requestTimeout(Duration.ofSeconds(30))
 *   .withToolsChangeHandler { tools =>
 *     println(s"Tools updated: ${tools.map(_.name).mkString(", ")}")
 *   }
 *   .build()
 *
 * client.initialize().flatMap { _ =>
 *   client.listTools().flatMap { tools =>
 *     if (tools.nonEmpty) {
 *       client.callTool(tools.head.name, Map.empty)
 *     } else Future.successful(())
 *   }
 * }.onComplete {
 *   case Success(_) => client.closeGracefully()
 *   case Failure(e) => client.close()
 * }
 * }}}
 *
 * @param underlying the underlying Java McpAsyncClient
 * @param ec         execution context for async operations
 */
class StreamableHttpMcpAsyncClient(
    private val underlying: io.modelcontextprotocol.client.McpAsyncClient
)(implicit ec: ExecutionContext) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[StreamableHttpMcpAsyncClient])

  // --------------------------
  // Lifecycle Methods
  // --------------------------

  /**
   * Initialize the connection to the MCP server.
   * Must be called before any other operations.
   */
  def initialize(): Future[McpSchema.InitializeResult] = {
    monoToFuture(underlying.initialize())
  }

  /**
   * Check if the client is initialized.
   */
  def isInitialized: Boolean = underlying.isInitialized

  /**
   * Close the client connection immediately.
   */
  def close(): Unit = underlying.close()

  /**
   * Gracefully close the client connection.
   */
  def closeGracefully(): Future[Unit] = {
    monoToFuture(underlying.closeGracefully()).map(_ => ())
  }

  // --------------------------
  // Server Information
  // --------------------------

  /**
   * Get the server capabilities.
   */
  def serverCapabilities: Option[McpSchema.ServerCapabilities] = {
    Option(underlying.getServerCapabilities)
  }

  /**
   * Get the server instructions.
   */
  def serverInstructions: Option[String] = {
    Option(underlying.getServerInstructions)
  }

  /**
   * Get the server implementation information.
   */
  def serverInfo: Option[McpSchema.Implementation] = {
    Option(underlying.getServerInfo)
  }

  /**
   * Get the client capabilities.
   */
  def clientCapabilities: McpSchema.ClientCapabilities = {
    underlying.getClientCapabilities
  }

  /**
   * Get the client implementation information.
   */
  def clientInfo: McpSchema.Implementation = {
    underlying.getClientInfo
  }

  // --------------------------
  // Basic Utilities
  // --------------------------

  /**
   * Send a ping to the server.
   */
  def ping(): Future[Unit] = {
    monoToFuture(underlying.ping()).map(_ => ())
  }

  // --------------------------
  // Tools
  // --------------------------

  /**
   * Call a tool on the server.
   */
  def callTool(toolName: String, arguments: Map[String, Any] = Map.empty): Future[McpSchema.CallToolResult] = {
    val request = new McpSchema.CallToolRequest(toolName, if (arguments.isEmpty) null else arguments.asJava)
    monoToFuture(underlying.callTool(request))
  }

  /**
   * List all available tools.
   */
  def listTools(): Future[List[McpSchema.Tool]] = {
    monoToFuture(underlying.listTools()).map(result => result.tools().asScala.toList)
  }

  /**
   * List tools with pagination.
   */
  def listToolsPaginated(cursor: String): Future[McpSchema.ListToolsResult] = {
    monoToFuture(underlying.listTools(cursor))
  }

  // --------------------------
  // Resources
  // --------------------------

  /**
   * List all available resources.
   */
  def listResources(): Future[List[McpSchema.Resource]] = {
    monoToFuture(underlying.listResources()).map(result => result.resources().asScala.toList)
  }

  /**
   * List resources with pagination.
   */
  def listResourcesPaginated(cursor: String): Future[McpSchema.ListResourcesResult] = {
    monoToFuture(underlying.listResources(cursor))
  }

  /**
   * Read a resource by URI.
   */
  def readResource(uri: String): Future[McpSchema.ReadResourceResult] = {
    val request = new McpSchema.ReadResourceRequest(uri)
    monoToFuture(underlying.readResource(request))
  }

  /**
   * Subscribe to resource updates.
   */
  def subscribeResource(uri: String): Future[Unit] = {
    val request = new McpSchema.SubscribeRequest(uri)
    monoToFuture(underlying.subscribeResource(request)).map(_ => ())
  }

  /**
   * Unsubscribe from resource updates.
   */
  def unsubscribeResource(uri: String): Future[Unit] = {
    val request = new McpSchema.UnsubscribeRequest(uri)
    monoToFuture(underlying.unsubscribeResource(request)).map(_ => ())
  }

  /**
   * List all available resource templates.
   */
  def listResourceTemplates(): Future[List[McpSchema.ResourceTemplate]] = {
    monoToFuture(underlying.listResourceTemplates()).map(result => result.resourceTemplates().asScala.toList)
  }

  // --------------------------
  // Prompts
  // --------------------------

  /**
   * List all available prompts.
   */
  def listPrompts(): Future[List[McpSchema.Prompt]] = {
    monoToFuture(underlying.listPrompts()).map(result => result.prompts().asScala.toList)
  }

  /**
   * List prompts with pagination.
   */
  def listPromptsPaginated(cursor: String): Future[McpSchema.ListPromptsResult] = {
    monoToFuture(underlying.listPrompts(cursor))
  }

  /**
   * Get a specific prompt.
   */
  def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Future[McpSchema.GetPromptResult] = {
    val request = new McpSchema.GetPromptRequest(name, if (arguments.isEmpty) null else arguments.asJava)
    monoToFuture(underlying.getPrompt(request))
  }

  // --------------------------
  // Logging
  // --------------------------

  /**
   * Set the logging level for server messages.
   */
  def setLoggingLevel(level: McpSchema.LoggingLevel): Future[Unit] = {
    monoToFuture(underlying.setLoggingLevel(level)).map(_ => ())
  }

  // --------------------------
  // Roots
  // --------------------------

  /**
   * Add a root directory.
   */
  def addRoot(root: McpSchema.Root): Future[Unit] = {
    monoToFuture(underlying.addRoot(root)).map(_ => ())
  }

  /**
   * Remove a root directory by URI.
   */
  def removeRoot(rootUri: String): Future[Unit] = {
    monoToFuture(underlying.removeRoot(rootUri)).map(_ => ())
  }

  /**
   * Send roots list changed notification.
   */
  def rootsListChangedNotification(): Future[Unit] = {
    monoToFuture(underlying.rootsListChangedNotification()).map(_ => ())
  }

  // --------------------------
  // Completions
  // --------------------------

  /**
   * Request completions for a prompt or resource.
   */
  def completeCompletion(request: McpSchema.CompleteRequest): Future[McpSchema.CompleteResult] = {
    monoToFuture(underlying.completeCompletion(request))
  }

  // --------------------------
  // Conversion Utilities
  // --------------------------

  /**
   * Convert a Reactor Mono to a Scala Future.
   */
  private def monoToFuture[T](mono: Mono[T]): Future[T] = {
    val promise = Promise[T]()
    mono.subscribe(
      (value: T) => promise.success(value),
      (error: Throwable) => promise.failure(error),
      () => if (!promise.isCompleted) promise.success(null.asInstanceOf[T])
    )
    promise.future
  }
}

object StreamableHttpMcpAsyncClient {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Create a new builder for StreamableHttpMcpAsyncClient.
   */
  def builder()(implicit ec: ExecutionContext): Builder = new Builder()

  /**
   * Builder for creating StreamableHttpMcpAsyncClient instances with comprehensive configuration.
   *
   * This builder supports all features of the MCP Java SDK async API:
   * - Streamable HTTP transport (not SSE)
   * - Custom request builders
   * - Async request customizers for dynamic headers (e.g., OAuth tokens)
   * - Change consumers for reactive notifications
   * - Logging and progress consumers
   * - Sampling and elicitation handlers
   */
  class Builder(implicit ec: ExecutionContext) {
    private var serverUrl: Option[String] = None
    private var clientName: String = "scala-mcp-client"
    private var clientVersion: String = "1.0.0"
    private var requestTimeout: Duration = Duration.ofSeconds(60)
    private var initializationTimeout: Duration = Duration.ofSeconds(30)
    private var capabilities: Option[McpSchema.ClientCapabilities] = None

    // Transport customization
    private var customRequestBuilder: Option[HttpRequest.Builder] = None
    private var asyncRequestCustomizer: Option[McpAsyncHttpClientRequestCustomizer] = None

    // Change handlers (Scala functions)
    private var toolsChangeHandler: Option[java.util.List[McpSchema.Tool] => Unit] = None
    private var resourcesChangeHandler: Option[java.util.List[McpSchema.Resource] => Unit] = None
    private var promptsChangeHandler: Option[java.util.List[McpSchema.Prompt] => Unit] = None

    // Notification handlers
    private var loggingHandler: Option[McpSchema.LoggingMessageNotification => Unit] = None
    private var progressHandler: Option[McpSchema.ProgressNotification => Unit] = None

    // Sampling and elicitation handlers
    private var samplingHandler: Option[McpSchema.CreateMessageRequest => Future[McpSchema.CreateMessageResult]] = None
    private var elicitationHandler: Option[McpSchema.ElicitRequest => Future[McpSchema.ElicitResult]] = None

    /**
     * Set the server URL for HTTP/HTTPS connections.
     */
    def serverUrl(url: String): Builder = {
      this.serverUrl = Some(url)
      this
    }

    /**
     * Set the client name and version.
     */
    def clientInfo(name: String, version: String): Builder = {
      this.clientName = name
      this.clientVersion = version
      this
    }

    /**
     * Set the request timeout.
     */
    def requestTimeout(timeout: Duration): Builder = {
      this.requestTimeout = timeout
      this
    }

    /**
     * Set the initialization timeout.
     */
    def initializationTimeout(timeout: Duration): Builder = {
      this.initializationTimeout = timeout
      this
    }

    /**
     * Set custom client capabilities.
     */
    def capabilities(caps: McpSchema.ClientCapabilities): Builder = {
      this.capabilities = Some(caps)
      this
    }

    /**
     * Set a custom HTTP request builder for adding headers or other request properties.
     *
     * Example:
     * {{{
     * val requestBuilder = HttpRequest.newBuilder()
     *   .header("X-API-Key", "my-api-key")
     *   .header("X-Custom-Header", "value")
     * builder.customRequestBuilder(requestBuilder)
     * }}}
     */
    def customRequestBuilder(builder: HttpRequest.Builder): Builder = {
      this.customRequestBuilder = Some(builder)
      this
    }

    /**
     * Set an async request customizer for dynamic request modification.
     *
     * This is useful for adding authentication headers that need to be fetched asynchronously,
     * such as OAuth tokens or session credentials.
     *
     * Example:
     * {{{
     * builder.asyncRequestCustomizer(new McpAsyncHttpClientRequestCustomizer {
     *   override def customize(
     *     builder: HttpRequest.Builder,
     *     method: String,
     *     endpoint: URI,
     *     body: String,
     *     context: McpTransportContext
     *   ): Publisher[HttpRequest.Builder] = {
     *     Mono.fromCallable(() => fetchAccessToken())
     *       .map(token => builder.copy().header("Authorization", s"Bearer $token"))
     *   }
     * })
     * }}}
     */
    def asyncRequestCustomizer(customizer: McpAsyncHttpClientRequestCustomizer): Builder = {
      this.asyncRequestCustomizer = Some(customizer)
      this
    }

    /**
     * Set a handler for tools change notifications.
     *
     * The handler will be called whenever the server's tool list changes.
     */
    def withToolsChangeHandler(handler: List[McpSchema.Tool] => Unit): Builder = {
      this.toolsChangeHandler = Some((tools: java.util.List[McpSchema.Tool]) => {
        handler(tools.asScala.toList)
      })
      this
    }

    /**
     * Set a handler for resources change notifications.
     *
     * The handler will be called whenever the server's resource list changes.
     */
    def withResourcesChangeHandler(handler: List[McpSchema.Resource] => Unit): Builder = {
      this.resourcesChangeHandler = Some((resources: java.util.List[McpSchema.Resource]) => {
        handler(resources.asScala.toList)
      })
      this
    }

    /**
     * Set a handler for prompts change notifications.
     *
     * The handler will be called whenever the server's prompt list changes.
     */
    def withPromptsChangeHandler(handler: List[McpSchema.Prompt] => Unit): Builder = {
      this.promptsChangeHandler = Some((prompts: java.util.List[McpSchema.Prompt]) => {
        handler(prompts.asScala.toList)
      })
      this
    }

    /**
     * Set a handler for logging notifications from the server.
     */
    def withLoggingHandler(handler: McpSchema.LoggingMessageNotification => Unit): Builder = {
      this.loggingHandler = Some(handler)
      this
    }

    /**
     * Set a handler for progress notifications from the server.
     */
    def withProgressHandler(handler: McpSchema.ProgressNotification => Unit): Builder = {
      this.progressHandler = Some(handler)
      this
    }

    /**
     * Set a handler for sampling requests from the server.
     *
     * This allows the server to request LLM completions from the client.
     */
    def withSamplingHandler(handler: McpSchema.CreateMessageRequest => Future[McpSchema.CreateMessageResult]): Builder = {
      this.samplingHandler = Some(handler)
      this
    }

    /**
     * Set a handler for elicitation requests from the server.
     */
    def withElicitationHandler(handler: McpSchema.ElicitRequest => Future[McpSchema.ElicitResult]): Builder = {
      this.elicitationHandler = Some(handler)
      this
    }

    /**
     * Build the StreamableHttpMcpAsyncClient with all configured settings.
     *
     * @throws IllegalArgumentException if server URL is not set
     * @return configured client ready to use
     */
    def build(): StreamableHttpMcpAsyncClient = {
      val url = serverUrl.getOrElse(
        throw new IllegalArgumentException("Server URL is required. Use serverUrl() to set the server endpoint.")
      )

      logger.info(s"Creating StreamableHttpMcpAsyncClient for: $url")

      // Create the streamable HTTP transport
      val transportBuilder = HttpClientStreamableHttpTransport.builder(url)

      // Apply custom request builder if provided
      customRequestBuilder.foreach(builder => transportBuilder.requestBuilder(builder))

      // Apply async request customizer if provided
      asyncRequestCustomizer.foreach(customizer => transportBuilder.asyncHttpRequestCustomizer(customizer))

      val transport: McpClientTransport = transportBuilder.build()

      // Create client info
      val clientInfo = new McpSchema.Implementation(clientName, clientVersion)

      // Create the async client builder
      val clientBuilder = McpClient.async(transport)
        .clientInfo(clientInfo)
        .requestTimeout(requestTimeout)
        .initializationTimeout(initializationTimeout)

      // Add capabilities if provided
      capabilities.foreach(caps => clientBuilder.capabilities(caps))

      // Add change consumers
      toolsChangeHandler.foreach { handler =>
        clientBuilder.toolsChangeConsumer((tools: java.util.List[McpSchema.Tool]) => {
          Mono.fromRunnable(() => Try(handler(tools)).recover {
            case e: Exception => logger.error("Error in tools change handler", e)
          })
        })
      }

      resourcesChangeHandler.foreach { handler =>
        clientBuilder.resourcesChangeConsumer((resources: java.util.List[McpSchema.Resource]) => {
          Mono.fromRunnable(() => Try(handler(resources)).recover {
            case e: Exception => logger.error("Error in resources change handler", e)
          })
        })
      }

      promptsChangeHandler.foreach { handler =>
        clientBuilder.promptsChangeConsumer((prompts: java.util.List[McpSchema.Prompt]) => {
          Mono.fromRunnable(() => Try(handler(prompts)).recover {
            case e: Exception => logger.error("Error in prompts change handler", e)
          })
        })
      }

      // Add notification handlers
      loggingHandler.foreach { handler =>
        clientBuilder.loggingConsumer((notification: McpSchema.LoggingMessageNotification) => {
          Mono.fromRunnable(() => Try(handler(notification)).recover {
            case e: Exception => logger.error("Error in logging handler", e)
          })
        })
      }

      progressHandler.foreach { handler =>
        clientBuilder.progressConsumer((notification: McpSchema.ProgressNotification) => {
          Mono.fromRunnable(() => Try(handler(notification)).recover {
            case e: Exception => logger.error("Error in progress handler", e)
          })
        })
      }

      // Add sampling handler
      samplingHandler.foreach { handler =>
        clientBuilder.sampling((request: McpSchema.CreateMessageRequest) => {
          val promise = Promise[McpSchema.CreateMessageResult]()
          handler(request).onComplete {
            case Success(result) => promise.success(result)
            case Failure(ex) => promise.failure(ex)
          }
          Mono.fromFuture(promise.future.toJava)
        })
      }

      // Add elicitation handler
      elicitationHandler.foreach { handler =>
        clientBuilder.elicitation((request: McpSchema.ElicitRequest) => {
          val promise = Promise[McpSchema.ElicitResult]()
          handler(request).onComplete {
            case Success(result) => promise.success(result)
            case Failure(ex) => promise.failure(ex)
          }
          Mono.fromFuture(promise.future.toJava)
        })
      }

      // Build and wrap in Scala client
      new StreamableHttpMcpAsyncClient(clientBuilder.build())
    }
  }

  // Extension to convert Scala Future to Java CompletableFuture
  implicit class FutureOps[T](future: Future[T])(implicit ec: ExecutionContext) {
    def toJava: java.util.concurrent.CompletableFuture[T] = {
      val cf = new java.util.concurrent.CompletableFuture[T]()
      future.onComplete {
        case Success(result) => cf.complete(result)
        case Failure(ex) => cf.completeExceptionally(ex)
      }
      cf
    }
  }
}
