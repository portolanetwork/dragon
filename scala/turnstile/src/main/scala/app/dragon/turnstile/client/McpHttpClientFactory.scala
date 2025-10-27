package app.dragon.turnstile.client

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.{HttpClientSseClientTransport, HttpClientStreamableHttpTransport, ServerParameters}
import io.modelcontextprotocol.spec.McpSchema.{ClientCapabilities, Implementation}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import java.time.Duration
import scala.concurrent.ExecutionContext

/**
 * Factory for creating MCP clients that connect to remote servers via HTTP/HTTPS.
 *
 * This factory provides methods to create both synchronous and asynchronous MCP clients
 * that can connect to remote MCP servers over HTTP.
 *
 * Example usage:
 * {{{
 * implicit val ec: ExecutionContext = ExecutionContext.global
 *
 * // Create an async HTTP client
 * val client = McpHttpClientFactory.createAsyncHttpClient(
 *   serverUrl = "http://localhost:8082/mcp",
 *   clientName = "MyApp",
 *   clientVersion = "1.0.0"
 * )
 *
 * // Use the client
 * client.initialize().flatMap { _ =>
 *   client.listTools()
 * }
 * }}}
 */
object McpHttpClientFactory {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Create an async MCP client that connects via HTTP/HTTPS.
   *
   * @param serverUrl          the URL of the MCP server (e.g., "http://localhost:8082/mcp")
   * @param clientName         the name of this client application (optional)
   * @param clientVersion      the version of this client application (optional)
   * @param requestTimeout     timeout for individual requests
   * @param initTimeout        timeout for initialization
   * @param capabilities       custom client capabilities (optional)
   * @param ec                 execution context for async operations
   * @return a configured McpAsyncClient ready to use
   */
  def createAsyncHttpClient(
      serverUrl: String,
      clientName: String = "scala-mcp-client",
      clientVersion: String = "1.0.0",
      requestTimeout: Duration = Duration.ofSeconds(60),
      initTimeout: Duration = Duration.ofSeconds(30),
      capabilities: Option[ClientCapabilities] = None
  )(implicit ec: ExecutionContext): McpAsyncClient = {

    logger.info(s"Creating async HTTP MCP client for server: $serverUrl")

    // Create HTTP transport
    val transport = createHttpTransport(serverUrl, requestTimeout)

    // Create client info
    val clientInfo = new Implementation(clientName, clientVersion)

    // Create client using the public API
    val javaClient = McpClient.async(transport)
      .clientInfo(clientInfo)
      .requestTimeout(requestTimeout)
      .initializationTimeout(initTimeout)

    // Add capabilities if provided
    val configuredClient = capabilities match {
      case Some(caps) => javaClient.capabilities(caps)
      case None       => javaClient
    }

    // Build and wrap in Scala client
    new McpAsyncClient(configuredClient.build())
  }

  /**
   * Create a synchronous MCP client that connects via HTTP/HTTPS.
   *
   * @param serverUrl          the URL of the MCP server
   * @param clientName         the name of this client application (optional)
   * @param clientVersion      the version of this client application (optional)
   * @param requestTimeout     timeout for individual requests
   * @param initTimeout        timeout for initialization
   * @param capabilities       custom client capabilities (optional)
   * @return a configured MCP client ready to use
   */
  def createSyncHttpClient(
      serverUrl: String,
      clientName: String = "scala-mcp-client",
      clientVersion: String = "1.0.0",
      requestTimeout: Duration = Duration.ofSeconds(60),
      initTimeout: Duration = Duration.ofSeconds(30),
      capabilities: Option[ClientCapabilities] = None
  ): io.modelcontextprotocol.client.McpSyncClient = {

    logger.info(s"Creating sync HTTP MCP client for server: $serverUrl")

    // Create HTTP transport
    val transport = createHttpTransport(serverUrl, requestTimeout)

    // Create client info
    val clientInfo = new Implementation(clientName, clientVersion)

    // Create client using the public API
    val javaClient = McpClient.sync(transport)
      .clientInfo(clientInfo)
      .requestTimeout(requestTimeout)
      .initializationTimeout(initTimeout)

    // Add capabilities if provided
    val configuredClient = capabilities match {
      case Some(caps) => javaClient.capabilities(caps)
      case None       => javaClient
    }

    // Build the client
    configuredClient.build()
  }


  /**
   * Create an HTTP transport for connecting to a remote MCP server.
   *
   * This uses SSE (Server-Sent Events) transport for bidirectional communication.
   *
   * @param serverUrl      the server URL
   * @param requestTimeout the request timeout
   * @return an McpTransport configured for HTTP/SSE
   */
  private def createHttpTransport(
      serverUrl: String,
      requestTimeout: Duration
  ): io.modelcontextprotocol.spec.McpClientTransport = {

    logger.debug(s"Creating HTTP/SSE transport for: $serverUrl")

    // Create HTTP/SSE client transport with the server URL
    HttpClientSseClientTransport.builder(serverUrl).build()
  }

  /**
   * Create a streamable HTTP transport for connecting to a remote MCP server.
   *
   * This uses streamable HTTP protocol for communication.
   *
   * @param serverUrl      the server URL
   * @param requestTimeout the request timeout
   * @return an McpTransport configured for streamable HTTP
   */
  /**
   * Create a client using streamable HTTP transport instead of SSE.
   *
   * @param serverUrl          the URL of the MCP server
   * @param clientName         the name of this client application
   * @param clientVersion      the version of this client application
   * @param requestTimeout     timeout for individual requests
   * @param initTimeout        timeout for initialization
   * @param capabilities       custom client capabilities (optional)
   * @param ec                 execution context for async operations
   * @return a configured McpAsyncClient using streamable HTTP
   */
  def createStreamableHttpClient(
      serverUrl: String,
      clientName: String = "scala-mcp-client",
      clientVersion: String = "1.0.0",
      requestTimeout: Duration = Duration.ofSeconds(60),
      initTimeout: Duration = Duration.ofSeconds(30),
      capabilities: Option[ClientCapabilities] = None
  )(implicit ec: ExecutionContext): McpAsyncClient = {

    logger.info(s"Creating async MCP client with streamable HTTP for server: $serverUrl")

    // Create streamable HTTP transport
    val transport = HttpClientStreamableHttpTransport.builder(serverUrl).build()

    // Create client info
    val clientInfo = new Implementation(clientName, clientVersion)

    // Create client using the public API
    val javaClient = McpClient.async(transport)
      .clientInfo(clientInfo)
      .requestTimeout(requestTimeout)
      .initializationTimeout(initTimeout)

    // Add capabilities if provided
    val configuredClient = capabilities match {
      case Some(caps) => javaClient.capabilities(caps)
      case None       => javaClient
    }

    // Build and wrap in Scala client
    new McpAsyncClient(configuredClient.build())
  }

  /**
   * Create an enhanced streamable HTTP client with full async API support.
   *
   * This method returns StreamableHttpMcpAsyncClient which provides access to
   * advanced features like:
   * - Custom request builders
   * - Async request customizers (for dynamic auth headers, etc.)
   * - Change consumers for reactive notifications
   * - Logging and progress handlers
   * - Sampling and elicitation support
   *
   * Example:
   * {{{
   * val client = McpHttpClientFactory.createEnhancedStreamableHttpClient(
   *   serverUrl = "http://localhost:8082/mcp",
   *   clientName = "my-app",
   *   clientVersion = "1.0.0"
   * ) { builder =>
   *   builder
   *     .withToolsChangeHandler { tools =>
   *       println(s"Tools updated: ${tools.map(_.name).mkString(", ")}")
   *     }
   *     .withLoggingHandler { notification =>
   *       println(s"Server log: ${notification.data}")
   *     }
   * }
   * }}}
   *
   * @param serverUrl          the URL of the MCP server
   * @param clientName         the name of this client application
   * @param clientVersion      the version of this client application
   * @param requestTimeout     timeout for individual requests
   * @param initTimeout        timeout for initialization
   * @param builderConfig      function to configure the builder with additional options
   * @param ec                 execution context for async operations
   * @return a configured StreamableHttpMcpAsyncClient
   */
  def createEnhancedStreamableHttpClient(
      serverUrl: String,
      clientName: String = "scala-mcp-client",
      clientVersion: String = "1.0.0",
      requestTimeout: Duration = Duration.ofSeconds(60),
      initTimeout: Duration = Duration.ofSeconds(30),
      builderConfig: StreamableHttpMcpAsyncClient.Builder => StreamableHttpMcpAsyncClient.Builder = identity
  )(implicit ec: ExecutionContext): StreamableHttpMcpAsyncClient = {

    logger.info(s"Creating enhanced streamable HTTP MCP client for server: $serverUrl")

    // Create base builder
    val baseBuilder = StreamableHttpMcpAsyncClient.builder()
      .serverUrl(serverUrl)
      .clientInfo(clientName, clientVersion)
      .requestTimeout(requestTimeout)
      .initializationTimeout(initTimeout)

    // Apply custom configuration and build
    builderConfig(baseBuilder).build()
  }

  /**
   * Get a builder for creating a fully customized StreamableHttpMcpAsyncClient.
   *
   * This provides direct access to the builder for maximum flexibility.
   *
   * Example:
   * {{{
   * val client = McpHttpClientFactory.streamableHttpClientBuilder()
   *   .serverUrl("http://localhost:8082/mcp")
   *   .clientInfo("my-app", "1.0.0")
   *   .requestTimeout(Duration.ofSeconds(30))
   *   .withToolsChangeHandler { tools => /* ... */ }
   *   .asyncRequestCustomizer(myCustomizer)
   *   .build()
   * }}}
   */
  def streamableHttpClientBuilder()(implicit ec: ExecutionContext): StreamableHttpMcpAsyncClient.Builder = {
    StreamableHttpMcpAsyncClient.builder()
  }
}
