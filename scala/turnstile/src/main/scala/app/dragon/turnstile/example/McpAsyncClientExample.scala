package app.dragon.turnstile.example

import app.dragon.turnstile.client.McpHttpClientFactory
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Example demonstrating how to use McpAsyncClient to connect to a remote MCP server.
 *
 * This example shows:
 * - Creating a client with streaming HTTP transport
 * - Initializing the connection
 * - Listing and calling tools
 * - Proper resource cleanup
 *
 * Usage:
 * {{{
 * // Run against a local MCP server
 * sbt "runMain app.dragon.turnstile.example.McpAsyncClientExample http://localhost:8082/mcp"
 * }}}
 */
object McpAsyncClientExample {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContext = ExecutionContext.global

  def main(args: Array[String]): Unit = {
    // Get server URL from command line args or use default
    val serverUrl = args.headOption.getOrElse("http://localhost:8082/mcp")

    logger.info(s"Connecting to MCP server at: $serverUrl")

    // Create the async client using streamable HTTP transport (guaranteed streaming)
    val client = McpHttpClientFactory.createStreamableHttpClient(
      serverUrl = serverUrl,
      clientName = "turnstile-example",
      clientVersion = "1.0.0"
    )

    // Execute the workflow
    val workflow: Future[Unit] = for {
      // 1. Initialize the connection
      initResult <- client.initialize()
      _ = logger.info(s"Connected to server: ${initResult.serverInfo().name()} v${initResult.serverInfo().version()}")
      _ = logger.info(s"Server capabilities: ${initResult.capabilities()}")

      // 2. List available tools
      tools <- client.listTools()
      _ = logger.info(s"Available tools: ${tools.map(_.name()).mkString(", ")}")

      // 3. Optionally call a tool if any exist
      _ <- if (tools.nonEmpty) {
        val firstTool = tools.head
        logger.info(s"Calling tool: ${firstTool.name()}")

        client.callTool(firstTool.name(), Map.empty).map { result =>
          logger.info(s"Tool result: $result")
        }.recover {
          case e: Exception =>
            logger.warn(s"Tool call failed: ${e.getMessage}")
        }
      } else {
        logger.info("No tools available to call")
        Future.successful(())
      }

      // 4. List resources
      resources <- client.listResources().recover {
        case e: Exception =>
          logger.warn(s"Failed to list resources: ${e.getMessage}")
          List.empty
      }
      _ = logger.info(s"Available resources: ${resources.map(_.uri()).mkString(", ")}")

      // 5. List prompts
      prompts <- client.listPrompts().recover {
        case e: Exception =>
          logger.warn(s"Failed to list prompts: ${e.getMessage}")
          List.empty
      }
      _ = logger.info(s"Available prompts: ${prompts.map(_.name()).mkString(", ")}")

    } yield ()

    // Handle result and cleanup
    workflow.onComplete {
      case Success(_) =>
        logger.info("MCP client workflow completed successfully")
        client.closeGracefully().onComplete { _ =>
          logger.info("Client closed gracefully")
          System.exit(0)
        }

      case Failure(exception) =>
        logger.error(s"MCP client workflow failed: ${exception.getMessage}", exception)
        client.close()
        System.exit(1)
    }

    // Keep the application running
    Thread.sleep(30000) // Wait up to 30 seconds for async operations
  }
}
