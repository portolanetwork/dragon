package app.dragon.turnstile.service.tools

import app.dragon.turnstile.actor.{ActorLookup, McpClientActor}
import app.dragon.turnstile.service.{AsyncToolHandler, McpTool}
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import reactor.core.publisher.Mono

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

/**
 * A namespaced tool that wraps a remote MCP tool.
 *
 * This tool forwards calls to a specific MCP client actor, allowing
 * the Turnstile server to proxy tools from remote MCP servers.
 *
 * @param toolSchema The tool schema from the remote MCP server
 * @param mcpClientActorId The ID of the MCP client actor to forward calls to
 * @param system The actor system (implicit)
 */
class NamespacedTool(
  toolSchema: McpSchema.Tool,
  mcpClientActorId: String
)(implicit system: ActorSystem[?]) extends McpTool {

  implicit val timeout: Timeout = 30.seconds
  implicit val sharding: ClusterSharding = ClusterSharding(system)
  implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  override def getSchema(): McpSchema.Tool = toolSchema

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request) => {
      logger.debug(s"Forwarding tool call for ${request.name()} to MCP client actor $mcpClientActorId")

      // Get the MCP client actor reference
      val clientActor = ActorLookup.getMcpClientActor(mcpClientActorId)

      // Forward the tool call request to the MCP client actor and convert Future to Mono
      val futureResult = clientActor.ask[Either[McpClientActor.McpClientError, McpSchema.CallToolResult]](
        replyTo => McpClientActor.McpToolCallRequest(request, replyTo)
      )

      // Convert Scala Future to CompletableFuture, then to Mono
      Mono.fromCompletionStage(futureResult.asJava.toCompletableFuture)
        .flatMap {
          case Right(result) =>
            logger.debug(s"Tool call succeeded for ${request.name()}")
            Mono.just(result)
          case Left(error) =>
            logger.error(s"Tool call failed for ${request.name()}: $error")
            Mono.error(new RuntimeException(s"MCP client error: $error"))
        }
    }
  }
}

object NamespacedTool {
  /**
   * Create a namespaced tool from a remote tool schema.
   *
   * @param toolSchema The tool schema from the remote MCP server
   * @param mcpClientActorId The ID of the MCP client actor to forward calls to
   * @param system The actor system
   * @return A new NamespacedTool instance
   */
  def apply(
    toolSchema: McpSchema.Tool,
    mcpClientActorId: String
  )(implicit system: ActorSystem[?]): NamespacedTool = {
    new NamespacedTool(toolSchema, mcpClientActorId)
  }
}
