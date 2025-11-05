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

package app.dragon.turnstile.service.tools

import app.dragon.turnstile.actor.{ActorLookup, McpClientActor}
import app.dragon.turnstile.service.{AsyncToolHandler, McpTool, McpUtils}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import reactor.core.publisher.Mono

import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*

/**
 * A namespaced tool that wraps a remote MCP tool.
 *
 * This tool forwards calls to a specific MCP client actor, allowing
 * the Turnstile server to proxy tools from remote MCP servers.
 *
 * @param fromSchema The tool schema from the remote MCP server
 * @param mcpServerUuid The ID of the MCP client actor to forward calls to
 * @param system The actor system (implicit)
 */
class NamespacedTool(
  fromSchema: McpSchema.Tool,
  toSchema: McpSchema.Tool,
  userId: String,
  mcpServerUuid: String
)(
  implicit system: ActorSystem[?]
) extends McpTool {

  implicit val timeout: Timeout = 30.seconds
  implicit val sharding: ClusterSharding = ClusterSharding(system)
  implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  override def getSchema(): McpSchema.Tool = fromSchema
  
  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request: McpSchema.CallToolRequest) => {
      logger.debug(s"Forwarding tool call for ${request.name()} to MCP client actor $mcpServerUuid")

      val requestWithNamespace = McpSchema.CallToolRequest.builder()
        .name(toSchema.name()) 
        .arguments(request.arguments())
        .meta(request.meta())
        .progressToken(request.progressToken())
        .build()
      
      // Forward the tool call request to the MCP client actor and convert Future to Mono
      val futureResult = 
        ActorLookup.getMcpClientActor(userId, mcpServerUuid)
          .ask[Either[McpClientActor.McpClientError, McpSchema.CallToolResult]](
            replyTo => McpClientActor.McpToolCallRequest(requestWithNamespace, replyTo)
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
   * @param fromSchema The tool schema from the remote MCP server
   * @param mcpServerUuid The ID of the MCP client actor to forward calls to
   * @param system The actor system
   * @return A new NamespacedTool instance
   */
  def apply(
    fromSchema: McpSchema.Tool,
    toSchema: McpSchema.Tool,
    userId: String,
    mcpServerUuid: String
  )(implicit system: ActorSystem[?]): NamespacedTool = {
    new NamespacedTool(fromSchema, toSchema,  userId, mcpServerUuid)
  }
}
