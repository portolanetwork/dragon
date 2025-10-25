package app.dragon.turnstile.client

import io.modelcontextprotocol.spec.McpSchema.*
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.{Flux, Mono}

import java.time.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Scala wrapper for the Java MCP Async Client that provides Scala-friendly APIs.
 *
 * This client wraps the Java `io.modelcontextprotocol.client.McpAsyncClient` and provides:
 * - Scala Future-based API instead of Reactor Mono/Flux
 * - Idiomatic Scala collections and Option types
 * - Simplified connection to remote MCP servers
 *
 * Example usage:
 * {{{
 * implicit val ec: ExecutionContext = ExecutionContext.global
 *
 * val client = McpAsyncClient.builder()
 *   .withServerUrl("http://localhost:8082/mcp")
 *   .withClientInfo("MyApp", "1.0.0")
 *   .build()
 *
 * val toolsFuture = client.initialize().flatMap { _ =>
 *   client.listTools()
 * }
 * }}}
 *
 * @param underlying the underlying Java McpAsyncClient
 * @param ec         the execution context for async operations
 */
class McpAsyncClient(
    private val underlying: io.modelcontextprotocol.client.McpAsyncClient
)(implicit ec: ExecutionContext) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[McpAsyncClient])

  // --------------------------
  // Lifecycle Methods
  // --------------------------

  /**
   * Initialize the connection to the MCP server.
   * Must be called before any other operations.
   */
  def initialize(): Future[InitializeResult] = {
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
  def getServerCapabilities: Option[ServerCapabilities] = {
    Option(underlying.getServerCapabilities)
  }

  /**
   * Get the server instructions.
   */
  def getServerInstructions: Option[String] = {
    Option(underlying.getServerInstructions)
  }

  /**
   * Get the server implementation information.
   */
  def getServerInfo: Option[Implementation] = {
    Option(underlying.getServerInfo)
  }

  /**
   * Get the client capabilities.
   */
  def getClientCapabilities: ClientCapabilities = {
    underlying.getClientCapabilities
  }

  /**
   * Get the client implementation information.
   */
  def getClientInfo: Implementation = {
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
  def callTool(toolName: String, arguments: Map[String, Any]): Future[CallToolResult] = {
    val request = new CallToolRequest(toolName, arguments.asJava)
    monoToFuture(underlying.callTool(request))
  }

  /**
   * Call a tool on the server with a request object.
   */
  def callTool(request: CallToolRequest): Future[CallToolResult] = {
    monoToFuture(underlying.callTool(request))
  }

  /**
   * List all available tools.
   */
  def listTools(): Future[List[Tool]] = {
    monoToFuture(underlying.listTools()).map(result => result.tools().asScala.toList)
  }

  /**
   * List tools with pagination.
   */
  def listTools(cursor: String): Future[ListToolsResult] = {
    monoToFuture(underlying.listTools(cursor))
  }

  // --------------------------
  // Resources
  // --------------------------

  /**
   * List all available resources.
   */
  def listResources(): Future[List[Resource]] = {
    monoToFuture(underlying.listResources()).map(result => result.resources().asScala.toList)
  }

  /**
   * List resources with pagination.
   */
  def listResources(cursor: String): Future[ListResourcesResult] = {
    monoToFuture(underlying.listResources(cursor))
  }

  /**
   * Read a resource by URI.
   */
  def readResource(uri: String): Future[ReadResourceResult] = {
    val request = new ReadResourceRequest(uri)
    monoToFuture(underlying.readResource(request))
  }

  /**
   * Read a resource.
   */
  def readResource(resource: Resource): Future[ReadResourceResult] = {
    monoToFuture(underlying.readResource(resource))
  }

  /**
   * Read a resource with a request object.
   */
  def readResource(request: ReadResourceRequest): Future[ReadResourceResult] = {
    monoToFuture(underlying.readResource(request))
  }

  /**
   * List all available resource templates.
   */
  def listResourceTemplates(): Future[List[ResourceTemplate]] = {
    monoToFuture(underlying.listResourceTemplates()).map(result => result.resourceTemplates().asScala.toList)
  }

  /**
   * List resource templates with pagination.
   */
  def listResourceTemplates(cursor: String): Future[ListResourceTemplatesResult] = {
    monoToFuture(underlying.listResourceTemplates(cursor))
  }

  /**
   * Subscribe to resource updates.
   */
  def subscribeResource(uri: String): Future[Unit] = {
    val request = new SubscribeRequest(uri)
    monoToFuture(underlying.subscribeResource(request)).map(_ => ())
  }

  /**
   * Subscribe to resource updates with a request object.
   */
  def subscribeResource(request: SubscribeRequest): Future[Unit] = {
    monoToFuture(underlying.subscribeResource(request)).map(_ => ())
  }

  /**
   * Unsubscribe from resource updates.
   */
  def unsubscribeResource(uri: String): Future[Unit] = {
    val request = new UnsubscribeRequest(uri)
    monoToFuture(underlying.unsubscribeResource(request)).map(_ => ())
  }

  /**
   * Unsubscribe from resource updates with a request object.
   */
  def unsubscribeResource(request: UnsubscribeRequest): Future[Unit] = {
    monoToFuture(underlying.unsubscribeResource(request)).map(_ => ())
  }

  // --------------------------
  // Prompts
  // --------------------------

  /**
   * List all available prompts.
   */
  def listPrompts(): Future[List[Prompt]] = {
    monoToFuture(underlying.listPrompts()).map(result => result.prompts().asScala.toList)
  }

  /**
   * List prompts with pagination.
   */
  def listPrompts(cursor: String): Future[ListPromptsResult] = {
    monoToFuture(underlying.listPrompts(cursor))
  }

  /**
   * Get a specific prompt.
   */
  def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Future[GetPromptResult] = {
    val request = new GetPromptRequest(name, if (arguments.isEmpty) null else arguments.asJava)
    monoToFuture(underlying.getPrompt(request))
  }

  /**
   * Get a prompt with a request object.
   */
  def getPrompt(request: GetPromptRequest): Future[GetPromptResult] = {
    monoToFuture(underlying.getPrompt(request))
  }

  // --------------------------
  // Logging
  // --------------------------

  /**
   * Set the logging level for server messages.
   */
  def setLoggingLevel(level: LoggingLevel): Future[Unit] = {
    monoToFuture(underlying.setLoggingLevel(level)).map(_ => ())
  }

  // --------------------------
  // Roots
  // --------------------------

  /**
   * Add a root directory.
   */
  def addRoot(root: Root): Future[Unit] = {
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
  def completeCompletion(request: CompleteRequest): Future[CompleteResult] = {
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

  /**
   * Convert a Reactor Flux to a Scala Future of List.
   */
  private def fluxToFutureList[T](flux: Flux[T]): Future[List[T]] = {
    val promise = Promise[List[T]]()
    val items = new java.util.ArrayList[T]()

    flux.subscribe(
      (item: T) => items.add(item),
      (error: Throwable) => promise.failure(error),
      () => promise.success(items.asScala.toList)
    )

    promise.future
  }
}

object McpAsyncClient {

  /**
   * Create a new builder for McpAsyncClient.
   */
  def builder()(implicit ec: ExecutionContext): Builder = new Builder()

  /**
   * Builder for creating McpAsyncClient instances.
   */
  class Builder(implicit ec: ExecutionContext) {
    private var serverUrl: Option[String] = None
    private var clientName: String = "scala-mcp-client"
    private var clientVersion: String = "1.0.0"
    private var requestTimeout: Duration = Duration.ofSeconds(60)
    private var initializationTimeout: Duration = Duration.ofSeconds(30)
    private var capabilities: Option[ClientCapabilities] = None

    /**
     * Set the server URL for HTTP/HTTPS connections.
     */
    def withServerUrl(url: String): Builder = {
      this.serverUrl = Some(url)
      this
    }

    /**
     * Set the client name and version.
     */
    def withClientInfo(name: String, version: String): Builder = {
      this.clientName = name
      this.clientVersion = version
      this
    }

    /**
     * Set the request timeout.
     */
    def withRequestTimeout(timeout: Duration): Builder = {
      this.requestTimeout = timeout
      this
    }

    /**
     * Set the initialization timeout.
     */
    def withInitializationTimeout(timeout: Duration): Builder = {
      this.initializationTimeout = timeout
      this
    }

    /**
     * Set custom client capabilities.
     */
    def withCapabilities(caps: ClientCapabilities): Builder = {
      this.capabilities = Some(caps)
      this
    }

    /**
     * Build the McpAsyncClient.
     *
     * This will create an async client with HTTP/SSE transport if a server URL is provided.
     */
    def build(): McpAsyncClient = {
      serverUrl match {
        case Some(url) =>
          McpHttpClientFactory.createAsyncHttpClient(
            serverUrl = url,
            clientName = clientName,
            clientVersion = clientVersion,
            requestTimeout = requestTimeout,
            initTimeout = initializationTimeout,
            capabilities = capabilities
          )
        case None =>
          throw new IllegalArgumentException(
            "Server URL is required. Use withServerUrl() to set the server endpoint."
          )
      }
    }
  }
}
