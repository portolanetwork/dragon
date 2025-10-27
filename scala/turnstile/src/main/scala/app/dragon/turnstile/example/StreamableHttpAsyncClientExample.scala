package app.dragon.turnstile.example

import app.dragon.turnstile.client.StreamableHttpMcpAsyncClient
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import org.reactivestreams.Publisher
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono

import java.net.URI
import java.net.http.HttpRequest
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Comprehensive example demonstrating StreamableHttpMcpAsyncClient features.
 *
 * This example shows:
 * - Creating a client with streamable HTTP transport (not SSE)
 * - Custom request headers
 * - Async request customization for dynamic authentication
 * - Reactive change handlers for tools, resources, and prompts
 * - Logging and progress notification handlers
 * - Context-aware requests
 * - Proper lifecycle management
 *
 * Usage:
 * {{{
 * # Basic usage
 * sbt "runMain app.dragon.turnstile.example.StreamableHttpAsyncClientExample http://localhost:8082/mcp"
 *
 * # With authentication
 * sbt "runMain app.dragon.turnstile.example.StreamableHttpAsyncClientExample http://localhost:8082/mcp --auth"
 * }}}
 */
object StreamableHttpAsyncClientExample {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContext = ExecutionContext.global

  def main(args: Array[String]): Unit = {
    // Parse arguments
    val serverUrl = args.headOption.getOrElse("http://localhost:8082/mcp")
    val withAuth = args.contains("--auth")

    logger.info("=" * 80)
    logger.info("StreamableHttpMcpAsyncClient - Comprehensive Example")
    logger.info("=" * 80)
    logger.info(s"Server URL: $serverUrl")
    logger.info(s"Authentication: ${if (withAuth) "enabled" else "disabled"}")
    logger.info("")

    // Create the client based on configuration
    val client = if (withAuth) {
      createAuthenticatedClient(serverUrl)
    } else {
      createBasicClient(serverUrl)
    }

    // Execute the workflow
    val workflow: Future[Unit] = for {
      // 1. Initialize the connection
      initResult <- client.initialize()
      _ = logInitialization(initResult)

      // 2. List and display tools
      tools <- client.listTools()
      _ = logTools(tools)

      // 3. Call a tool if available
      _ <- callToolIfAvailable(client, tools)

      // 4. List and display resources
      resources <- client.listResources().recover {
        case e: Exception =>
          logger.warn(s"Failed to list resources: ${e.getMessage}")
          List.empty
      }
      _ = logResources(resources)

      // 5. List and display prompts
      prompts <- client.listPrompts().recover {
        case e: Exception =>
          logger.warn(s"Failed to list prompts: ${e.getMessage}")
          List.empty
      }
      _ = logPrompts(prompts)

      // 6. Demonstrate ping
      _ <- client.ping()
      _ = logger.info("✓ Ping successful")

    } yield ()

    // Handle result and cleanup
    workflow.onComplete {
      case Success(_) =>
        logger.info("")
        logger.info("=" * 80)
        logger.info("✓ Workflow completed successfully")
        logger.info("=" * 80)
        client.closeGracefully().onComplete { _ =>
          logger.info("✓ Client closed gracefully")
          System.exit(0)
        }

      case Failure(exception) =>
        logger.error("")
        logger.error("=" * 80)
        logger.error(s"✗ Workflow failed: ${exception.getMessage}", exception)
        logger.error("=" * 80)
        client.close()
        System.exit(1)
    }

    // Keep the application running
    Thread.sleep(60000) // Wait up to 60 seconds for async operations
  }

  /**
   * Create a basic client with standard features:
   * - Change handlers for reactive notifications
   * - Logging and progress handlers
   * - Custom headers
   */
  private def createBasicClient(serverUrl: String): StreamableHttpMcpAsyncClient = {
    logger.info("Creating basic StreamableHttpMcpAsyncClient...")

    // Custom request builder with static headers
    val requestBuilder = HttpRequest.newBuilder()
      .header("X-Client-Type", "scala-example")
      .header("X-Client-Feature", "streamable-http")

    StreamableHttpMcpAsyncClient.builder()
      .serverUrl(serverUrl)
      .clientInfo("streamable-http-example", "1.0.0")
      .requestTimeout(java.time.Duration.ofSeconds(30))
      .initializationTimeout(java.time.Duration.ofSeconds(15))
      .customRequestBuilder(requestBuilder)
      .withToolsChangeHandler { tools =>
        logger.info(s"[NOTIFICATION] Tools changed: ${tools.map(_.name()).mkString(", ")}")
      }
      .withResourcesChangeHandler { resources =>
        logger.info(s"[NOTIFICATION] Resources changed: ${resources.map(_.uri()).mkString(", ")}")
      }
      .withPromptsChangeHandler { prompts =>
        logger.info(s"[NOTIFICATION] Prompts changed: ${prompts.map(_.name()).mkString(", ")}")
      }
      .withLoggingHandler { notification =>
        val level = notification.level()
        val data = notification.data()
        logger.info(s"[SERVER LOG - $level] $data")
      }
      .withProgressHandler { notification =>
        val progress = notification.progress()
        val total = notification.total()
        val progressStr = if (total != null) s"$progress / $total" else s"$progress"
        logger.info(s"[PROGRESS] ${notification.progressToken()}: $progressStr")
      }
      .build()
  }

  /**
   * Create an authenticated client with async request customization.
   *
   * This demonstrates how to add dynamic authentication headers
   * (e.g., OAuth tokens fetched asynchronously).
   */
  private def createAuthenticatedClient(serverUrl: String): StreamableHttpMcpAsyncClient = {
    logger.info("Creating authenticated StreamableHttpMcpAsyncClient...")

    // Create async request customizer for dynamic authentication
    val authCustomizer = new McpAsyncHttpClientRequestCustomizer {
      override def customize(
          builder: HttpRequest.Builder,
          method: String,
          endpoint: URI,
          body: String,
          context: McpTransportContext
      ): Publisher[HttpRequest.Builder] = {
        // Simulate async token fetch
        Mono.fromCallable(() => {
          logger.debug(s"Fetching auth token for request: $method $endpoint")
          fetchAccessToken(context)
        }).map { token =>
          logger.debug(s"Adding auth header with token: ${token.take(10)}...")
          builder.copy()
            .header("Authorization", s"Bearer $token")
            .header("X-Request-ID", java.util.UUID.randomUUID().toString)
        }
      }
    }

    StreamableHttpMcpAsyncClient.builder()
      .serverUrl(serverUrl)
      .clientInfo("streamable-http-auth-example", "1.0.0")
      .requestTimeout(java.time.Duration.ofSeconds(30))
      .asyncRequestCustomizer(authCustomizer)
      .withToolsChangeHandler { tools =>
        logger.info(s"[NOTIFICATION] Tools changed: ${tools.map(_.name()).mkString(", ")}")
      }
      .withLoggingHandler { notification =>
        logger.info(s"[SERVER LOG] ${notification.data()}")
      }
      .build()
  }

  /**
   * Simulate fetching an access token asynchronously.
   *
   * In a real application, this would:
   * - Fetch from an OAuth provider
   * - Read from a token cache with refresh logic
   * - Retrieve from context-specific storage
   */
  private def fetchAccessToken(context: McpTransportContext): String = {
    // In production, you might extract user info from context
    val userId = Option(context.get("user_id")).map(_.toString).getOrElse("anonymous")

    // Simulate token fetch delay
    Thread.sleep(100)

    // Generate a mock token
    s"mock-token-${userId}-${System.currentTimeMillis()}"
  }

  /**
   * Log initialization results.
   */
  private def logInitialization(result: McpSchema.InitializeResult): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info("CONNECTION INITIALIZED")
    logger.info("=" * 80)
    logger.info(s"Protocol Version: ${result.protocolVersion()}")
    logger.info(s"Server: ${result.serverInfo().name()} v${result.serverInfo().version()}")

    val capabilities = result.capabilities()
    logger.info("")
    logger.info("Server Capabilities:")

    if (capabilities.tools() != null) {
      logger.info(s"  ✓ Tools: ${if (capabilities.tools().listChanged()) "list_changed supported" else "supported"}")
    }

    if (capabilities.resources() != null) {
      logger.info(s"  ✓ Resources: " +
        s"${if (capabilities.resources().subscribe()) "subscribable" else "supported"}, " +
        s"${if (capabilities.resources().listChanged()) "list_changed supported" else ""}")
    }

    if (capabilities.prompts() != null) {
      logger.info(s"  ✓ Prompts: ${if (capabilities.prompts().listChanged()) "list_changed supported" else "supported"}")
    }

    if (capabilities.logging() != null) {
      logger.info("  ✓ Logging supported")
    }
  }

  /**
   * Log available tools.
   */
  private def logTools(tools: List[McpSchema.Tool]): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info(s"AVAILABLE TOOLS (${tools.size})")
    logger.info("=" * 80)

    if (tools.isEmpty) {
      logger.info("  (none)")
    } else {
      tools.foreach { tool =>
        logger.info(s"  • ${tool.name()}")
        logger.info(s"    ${tool.description()}")

        if (tool.inputSchema() != null) {
          val schema = tool.inputSchema()
          if (schema.properties() != null && !schema.properties().isEmpty) {
            val propertyNames = schema.properties().keySet().asScala.mkString(", ")
            logger.info(s"    Parameters: $propertyNames")
          }
        }
      }
    }
  }

  /**
   * Log available resources.
   */
  private def logResources(resources: List[McpSchema.Resource]): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info(s"AVAILABLE RESOURCES (${resources.size})")
    logger.info("=" * 80)

    if (resources.isEmpty) {
      logger.info("  (none)")
    } else {
      resources.foreach { resource =>
        logger.info(s"  • ${resource.uri()}")
        if (resource.name() != null) {
          logger.info(s"    Name: ${resource.name()}")
        }
        if (resource.description() != null) {
          logger.info(s"    ${resource.description()}")
        }
      }
    }
  }

  /**
   * Log available prompts.
   */
  private def logPrompts(prompts: List[McpSchema.Prompt]): Unit = {
    logger.info("")
    logger.info("=" * 80)
    logger.info(s"AVAILABLE PROMPTS (${prompts.size})")
    logger.info("=" * 80)

    if (prompts.isEmpty) {
      logger.info("  (none)")
    } else {
      prompts.foreach { prompt =>
        logger.info(s"  • ${prompt.name()}")
        if (prompt.description() != null) {
          logger.info(s"    ${prompt.description()}")
        }
      }
    }
  }

  /**
   * Call a tool if any are available.
   */
  private def callToolIfAvailable(
      client: StreamableHttpMcpAsyncClient,
      tools: List[McpSchema.Tool]
  ): Future[Unit] = {
    if (tools.isEmpty) {
      logger.info("")
      logger.info("No tools available to call")
      return Future.successful(())
    }

    // Find the echo tool or use the first available
    val toolToCall = tools.find(_.name() == "echo").getOrElse(tools.head)

    logger.info("")
    logger.info("=" * 80)
    logger.info(s"CALLING TOOL: ${toolToCall.name()}")
    logger.info("=" * 80)

    val arguments = if (toolToCall.name() == "echo") {
      Map("message" -> "Hello from StreamableHttpMcpAsyncClient!")
    } else {
      Map.empty[String, Any]
    }

    client.callTool(toolToCall.name(), arguments).map { result =>
      logger.info(s"Tool call successful:")

      if (result.content() != null) {
        result.content().asScala.foreach { content =>
          content.`type`() match {
            case "text" =>
              logger.info(s"  Text: ${content.asInstanceOf[McpSchema.TextContent].text()}")
            case "image" =>
              logger.info(s"  Image: ${content.asInstanceOf[McpSchema.ImageContent].mimeType()}")
            case "resource" =>
              val resContent = content.asInstanceOf[McpSchema.EmbeddedResource]
              logger.info(s"  Resource: ${resContent.resource().uri()}")
            case other =>
              logger.info(s"  Content type: $other")
          }
        }
      }

      if (result.isError() != null && result.isError()) {
        logger.warn("  Tool reported an error")
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Tool call failed: ${e.getMessage}", e)
    }
  }
}
