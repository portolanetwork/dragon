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

package app.dragon.turnstile.mcp_tools.impl

import app.dragon.turnstile.mcp_client.McpClientActor
import app.dragon.turnstile.mcp_tools.{AsyncToolHandler, McpTool, McpUtils}
import app.dragon.turnstile.utils.ActorLookup
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import reactor.core.publisher.Mono
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*

/**
 * A tool that dispatches tool calls to downstream MCP servers.
 *
 * This tool accepts a server UUID, tool name, and arguments, and forwards
 * the tool call to the appropriate McpClientActor.
 *
 * @param userId The user identifier
 * @param tenant The tenant identifier (default: "default")
 * @param system The actor system (implicit)
 * @param sharding The cluster sharding (implicit)
 * @param ec The execution context (implicit)
 * @param db The database instance (implicit)
 */
class ExecTool(
  userId: String,
  tenant: String = "default"
)(
  implicit system: ActorSystem[?],
  sharding: ClusterSharding,
  ec: ExecutionContext,
  db: Database
) extends McpTool {

  implicit val timeout: Timeout = 30.seconds

  override def getSchema(): McpSchema.Tool = {
    McpUtils.createToolSchemaBuilder(
      "exec_tool",
      "Execute a tool on a downstream MCP server by UUID"
    )
      .inputSchema(
        McpUtils.createObjectSchema(
          properties = Map(
            "mcpServerUuid" -> Map(
              "type" -> "string",
              "description" -> "The UUID of the MCP server that hosts the tool."
            ),
            "toolName" -> Map(
              "type" -> "string",
              "description" -> "The name of the tool to execute on the target MCP server."
            ),
            "arguments" -> Map(
              "type" -> "object",
              "description" -> "The arguments to pass to the tool, matching that tool's schema."
            )
          ),
          required = Seq("mcpServerUuid", "toolName")
        )
      )
      .build()
  }

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request: McpSchema.CallToolRequest) => {
      // Extract parameters from the request
      val mcpServerUuid = McpUtils.getStringArg(request, "mcpServerUuid")
      val toolName = McpUtils.getStringArg(request, "toolName")
      val arguments = McpUtils.getObjectArg(request, "arguments")

      logger.debug(s"ExecTool: dispatching tool '$toolName' to server UUID '$mcpServerUuid'")

      // Convert arguments Map[String, Any] to Java Map for CallToolRequest
      val javaArguments: java.util.Map[String, Any] = arguments match {
        case Some(args) =>
          val javaMap = new java.util.HashMap[String, Any]()
          args.foreach { case (k, v) => javaMap.put(k, v) }
          javaMap
        case None =>
          new java.util.HashMap[String, Any]()
      }

      // Create the tool call request
      val toolCallRequest = McpSchema.CallToolRequest.builder()
        .name(toolName)
        .arguments(javaArguments)
        .meta(request.meta())
        .progressToken(request.progressToken())
        .build()

      // Forward to McpClientActor using the provided UUID
      val futureResult = ActorLookup.getMcpClientActor(userId, mcpServerUuid)
        .ask[Either[McpClientActor.McpClientError, McpSchema.CallToolResult]](
          replyTo => McpClientActor.McpToolCallRequest(toolCallRequest, replyTo)
        )
        .map {
          case Right(result) =>
            logger.debug(s"ExecTool: tool call succeeded for '$toolName' on server UUID '$mcpServerUuid'")
            Right(result)
          case Left(error) =>
            logger.error(s"ExecTool: tool call failed for '$toolName' on server UUID '$mcpServerUuid': $error")
            Left(error)
        }

      // Convert Future to Mono
      Mono.fromCompletionStage(futureResult.asJava.toCompletableFuture)
        .flatMap {
          case Right(result) => Mono.just(result)
          case Left(error) => Mono.error(new RuntimeException(s"ExecTool error: $error"))
        }
    }
  }
}

object ExecTool {
  /**
   * Create an ExecTool instance.
   *
   * @param userId The user identifier
   * @param tenant The tenant identifier (default: "default")
   * @param system The actor system
   * @param sharding The cluster sharding
   * @param ec The execution context
   * @param db The database instance
   * @return A new ExecTool instance
   */
  def apply(
    userId: String,
    tenant: String = "default"
  )(implicit
    system: ActorSystem[?],
    sharding: ClusterSharding,
    ec: ExecutionContext,
    db: Database
  ): ExecTool = {
    new ExecTool(userId, tenant)
  }
}