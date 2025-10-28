package app.dragon.turnstile.example

import io.modelcontextprotocol.client.{McpAsyncClient, McpClient}
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono

import java.net.http.HttpRequest
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Comprehensive example demonstrating the official MCP Java SDK's McpAsyncClient with HTTP streaming transport.
 *
 * This example shows:
 * - Creating a client with the official HttpClientStreamableHttpTransport
 * - Using McpClient.async() factory method for client creation
 * - Custom request headers via HttpRequest.Builder
 * - Reactive change handlers for tools, resources, and prompts using Function<T, Mono<Void>>
 * - Logging and progress notification handlers
 * - Proper lifecycle management with graceful shutdown
 * - Converting Reactor Mono results to Scala Futures
 *
 * Usage:
 * {{{
 * # Basic usage
 * sbt "runMain app.dragon.turnstile.example.StreamableHttpAsyncClientExample http://localhost:8082"
 *
 * # With custom endpoint
 * sbt "runMain app.dragon.turnstile.example.StreamableHttpAsyncClientExample http://localhost:8082 /custom-mcp"
 * }}}
 */
object StreamableHttpAsyncClientExample {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContext = ExecutionContext.global

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

  def main(args: Array[String]): Unit = {
    // Parse arguments
    //val serverUrl = args.headOption.getOrElse("https://demo-day.mcp.cloudflare.com/") // SSE only
    //val serverUrl = args.headOption.getOrElse("https://localhost:8083/")
    //val serverUrl = args.headOption.getOrElse("https://mcp.deepwiki.com/mcp")
    val serverUrl = args.headOption.getOrElse("http://localhost:8082/mcp")
    val endpoint = args.lift(1).getOrElse("/mcp")
    //val endpoint = args.lift(1).getOrElse("/sse") // des not support sse transport

    logger.info("=" * 80)
    logger.info("Official MCP Java SDK - McpAsyncClient with HTTP Streaming")
    logger.info("=" * 80)
    logger.info(s"Server URL: $serverUrl")
    logger.info(s"Endpoint: $endpoint")
    logger.info("")

    // Create the client
    val client = createClient(serverUrl, endpoint)

    // Execute the workflow - convert Reactor Mono to Scala Future
    val workflow: Future[Unit] = for {
      // 1. Initialize the connection
      initResult <- monoToFuture(client.initialize()).recoverWith {
        case e: Exception =>
          logger.error("Failed to initialize client:", e)
          logger.error(s"Server URL: $serverUrl, Endpoint: $endpoint")
          Future.failed(e)
      }
      _ = logInitialization(initResult)


      // 2. List and display tools
      toolsResult <- monoToFuture(client.listTools())
      _ = logTools(toolsResult.tools().asScala.toList)

      // 3. Call a tool if available
      _ <- callToolIfAvailable(client, toolsResult.tools().asScala.toList)

      // 4. List and display resources
      resources <- monoToFuture(client.listResources())
        .map(_.resources().asScala.toList)
        .recover {
          case e: Exception =>
            logger.warn(s"Failed to list resources: ${e.getMessage}")
            List.empty
        }
      _ = logResources(resources)

      // 5. List and display prompts
      prompts <- monoToFuture(client.listPrompts())
        .map(_.prompts().asScala.toList)
        .recover {
          case e: Exception =>
            logger.warn(s"Failed to list prompts: ${e.getMessage}")
            List.empty
        }
      _ = logPrompts(prompts)

      // 6. Demonstrate ping
      _ <- monoToFuture(client.ping())
      _ = logger.info("✓ Ping successful")

    } yield ()

    // Handle result and cleanup
    workflow.onComplete {
      case Success(_) =>
        logger.info("")
        logger.info("=" * 80)
        logger.info("✓ Workflow completed successfully")
        logger.info("=" * 80)
        monoToFuture(client.closeGracefully()).onComplete { _ =>
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
   * Create a client using the official MCP Java SDK with HttpClientStreamableHttpTransport.
   *
   * This demonstrates:
   * - Building HttpClientStreamableHttpTransport with custom configuration
   * - Using McpClient.async() factory method
   * - Adding reactive change handlers using Function<T, Mono<Void>>
   * - Custom HTTP headers via HttpRequest.Builder
   * - Logging and progress handlers
   */
  private def createClient(serverUrl: String, endpoint: String): McpAsyncClient = {
    logger.info("Creating McpAsyncClient with HttpClientStreamableHttpTransport...")

    // Custom request builder with static headers
    val requestBuilder = HttpRequest.newBuilder()
      .header("X-Client-Type", "scala-example")
      .header("X-Client-Feature", "official-mcp-sdk")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json,text/event-stream")
      .header("User-Agent", "Turnstile-MCP-Client/1.0.0")

    // Build the HTTP streaming transport
    val transport = HttpClientStreamableHttpTransport.builder(serverUrl)
      .endpoint(endpoint)
      //.requestBuilder(requestBuilder)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .resumableStreams(true)
      .build()

    // Build the async client using the official McpClient factory
    McpClient.async(transport)
      .requestTimeout(java.time.Duration.ofSeconds(30))
      .initializationTimeout(java.time.Duration.ofSeconds(15))
      .clientInfo(new McpSchema.Implementation("turnstile-scala-example", "1.0.0"))
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
          val progress = notification.progress()
          val total = notification.total()
          val progressStr = if (total != null) s"$progress / $total" else s"$progress"
          logger.info(s"[PROGRESS] ${notification.progressToken()}: $progressStr")
        })
      )
      .build()
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
      client: McpAsyncClient,
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

    // Create the tool call request
    val arguments = if (toolToCall.name() == "echo") {
      java.util.Map.of("message", "Hello from Official MCP Java SDK!")
    } else {
      java.util.Collections.emptyMap[String, Object]()
    }

    val callToolRequest = new McpSchema.CallToolRequest(toolToCall.name(), arguments)

    monoToFuture(client.callTool(callToolRequest)).map { result =>
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
