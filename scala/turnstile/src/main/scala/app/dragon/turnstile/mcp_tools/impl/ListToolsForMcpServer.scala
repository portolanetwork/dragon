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

import app.dragon.turnstile.db.{DbInterface, DbNotFound}
import app.dragon.turnstile.mcp_client.McpClientActor
import app.dragon.turnstile.mcp_tools.{AsyncToolHandler, McpTool, McpUtils}
import app.dragon.turnstile.utils.ActorLookup
import com.fasterxml.jackson.databind.JsonNode
import io.circe.Json
import io.circe.syntax.*
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import reactor.core.publisher.Mono
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*
import io.circe.jackson.jacksonToCirce
import io.modelcontextprotocol.json.McpJsonMapper

import scala.jdk.CollectionConverters.*
import java.util.UUID
import scala.util.{Try, Success, Failure}

/**
 * List MCP tools - lists all available tools from a specific MCP server.
 *
 * This tool queries a downstream MCP server to retrieve its available tools,
 * including:
 * - Tool name
 * - Tool description
 * - Input schema (JSON schema for tool parameters)
 *
 * This is useful for:
 * - Discovering capabilities of MCP servers
 * - Building dynamic tool routing
 * - Documentation and introspection
 *
 * Example usage (list all tools):
 * {{{
 * {
 *   "name": "list_tools_for_mcp_server",
 *   "arguments": {
 *     "mcpServerUuid": "12345678-1234-1234-1234-123456789abc"
 *   }
 * }
 * }}}
 *
 * Example usage (filter specific tools with exact match):
 * {{{
 * {
 *   "name": "list_tools_for_mcp_server",
 *   "arguments": {
 *     "mcpServerUuid": "12345678-1234-1234-1234-123456789abc",
 *     "toolNames": ["fetch_data", "update_record"]
 *   }
 * }
 * }}}
 *
 * Example usage (filter with regex patterns):
 * {{{
 * {
 *   "name": "list_tools_for_mcp_server",
 *   "arguments": {
 *     "mcpServerUuid": "12345678-1234-1234-1234-123456789abc",
 *     "toolNames": ["exact_tool", "regex:.*_helper", "regex:^get_.*"]
 *   }
 * }
 * }}}
 *
 * Returns JSON:
 * {{{
 * {
 *   "server": "my-server",
 *   "serverUuid": "12345678-1234-1234-1234-123456789abc",
 *   "tools": [
 *     {
 *       "name": "fetch_data",
 *       "description": "Fetches data from the API",
 *       "inputSchema": {
 *         "type": "object",
 *         "properties": {
 *           "id": { "type": "string" }
 *         }
 *       }
 *     }
 *   ],
 *   "count": 1
 * }
 * }}}
 *
 * @param userId The user identifier
 * @param tenant The tenant identifier (default: "default")
 * @param system The actor system (implicit)
 * @param sharding The cluster sharding (implicit)
 * @param ec The execution context (implicit)
 * @param db The database instance (implicit)
 */


class ListToolsForMcpServer(
  userId: String,
  tenant: String = "default"
)(
  implicit system: ActorSystem[?],
  sharding: ClusterSharding,
  ec: ExecutionContext,
  db: Database
) extends McpTool {

  implicit val timeout: Timeout = 120.seconds

  /**
   * Match a tool name against a pattern.
   * Supports exact matching and regex patterns with 'regex:' prefix.
   *
   * @param toolName The tool name to match
   * @param pattern The pattern (exact string or regex with prefix)
   * @return Try[Boolean] - Success(true) if matched, Success(false) if not matched, Failure if invalid regex
   */
  private def matchPattern(toolName: String, pattern: String): Try[Boolean] = Try {
    if (pattern.startsWith("regex:")) {
      val regexPattern = pattern.stripPrefix("regex:")
      val regex = regexPattern.r
      regex.findFirstIn(toolName).isDefined
    } else {
      // Exact match
      toolName == pattern
    }
  }

  override def getSchema(): McpSchema.Tool = {
    import scala.jdk.CollectionConverters.*

    // Build the toolNames array schema manually
    val toolNamesSchema = Map(
      "type" -> "array",
      "description" -> "Optional list of tool names to filter. Use [\"*\"] to list all tools. Supports exact matches and regex patterns with 'regex:' prefix (e.g., 'regex:.*_helper', 'regex:^get_.*').",
      "items" -> Map("type" -> "string").asJava.asInstanceOf[Object],
      "default" -> java.util.List.of("*").asInstanceOf[Object]
    ).asJava.asInstanceOf[Object]

    val properties = Map(
      "mcpServerUuid" -> Map(
        "type" -> "string",
        "description" -> "The MCP server UUID to list tools from"
      ).asJava.asInstanceOf[Object],
      "toolNames" -> toolNamesSchema
    ).asJava

    val schema = new McpSchema.JsonSchema(
      "object",
      properties,
      java.util.List.of("mcpServerUuid"),
      null, // additionalProperties
      null, // defs
      null  // definitions
    )

    McpUtils.createToolSchemaBuilder(
      name = "list_tools_for_mcp_server",
      description = "Lists all available tools from a specific MCP server by UUID, optionally filtered by tool names"
    )
      .inputSchema(schema)
      .build()
  }

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request) => {
      val serverUuidStr = McpUtils.getStringArg(request, "mcpServerUuid")
      val serverUuid = UUID.fromString(serverUuidStr)

      // Extract toolNames parameter, defaulting to Set("*") if not provided
      val toolNamesFilter = Option(request.arguments())
        .flatMap(args => Option(args.get("toolNames")))
        .map {
          case list: java.util.List[?] => list.asScala.map(_.toString).toSet
          case _ => Set("*")
        }
        .getOrElse(Set("*"))

      logger.debug(s"ListMcpTools: listing tools from server UUID '$serverUuid' with filter: $toolNamesFilter")

      // Query the MCP server for tools
      val futureResult = for {
        // Step 1: Look up the MCP server by UUID
        dbResult <- DbInterface.findMcpServerByUuid(serverUuid)
        mcpServerRow <- dbResult match {
          case Right(row) =>
            logger.debug(s"ListMcpTools: found server '${row.name}' with UUID ${row.uuid}")
            scala.concurrent.Future.successful(row)
          case Left(DbNotFound) =>
            logger.error(s"ListMcpTools: server with UUID '$serverUuid' not found")
            scala.concurrent.Future.failed(new RuntimeException(s"MCP server with UUID '$serverUuid' not found"))
          case Left(error) =>
            logger.error(s"ListMcpTools: database error: ${error.message}")
            scala.concurrent.Future.failed(new RuntimeException(s"Database error: ${error.message}"))
        }

        // Step 3: Request the list of tools
        clientActor = ActorLookup.getMcpClientActor(userId, mcpServerRow.uuid.toString)
        actorResult <- clientActor.ask[Either[McpClientActor.McpClientError, McpSchema.ListToolsResult]](
          replyTo => McpClientActor.McpListTools(replyTo)
        )

        // Step 4: Process the result and convert to JSON
        jsonResult <- actorResult match {
          case Right(listResult) =>
            logger.debug(s"ListMcpTools: successfully listed ${listResult.tools().size()} tools from server '${mcpServerRow.name}' (UUID: $serverUuid)")

            // Convert Java List to Scala Seq
            val allTools = listResult.tools().asScala.toSeq

            // Apply filter: if toolNamesFilter contains "*", return all tools; otherwise filter by pattern
            val filteredTools = if (toolNamesFilter.contains("*")) {
              allTools
            } else {
              allTools.filter { tool =>
                toolNamesFilter.exists { pattern =>
                  matchPattern(tool.name(), pattern) match {
                    case Success(matched) =>
                      if (matched) {
                        logger.debug(s"ListMcpTools: tool '${tool.name()}' matched pattern '$pattern'")
                      }
                      matched
                    case Failure(e) =>
                      logger.warn(s"ListMcpTools: invalid regex pattern '$pattern': ${e.getMessage}")
                      false
                  }
                }
              }
            }

            // Build JSON for filtered tools
            val toolsJson = filteredTools.map { tool =>
                Json.obj(
                  "name" -> tool.name().asJson,
                  "description" -> Option(tool.description()).asJson,
                  "inputSchema" -> jacksonToCirce(
                    McpJsonMapper.getDefault.convertValue(tool.inputSchema(), classOf[JsonNode])
                  )
                )
              }

            logger.debug(s"ListMcpTools: returning ${toolsJson.size} tools after filtering (filter: $toolNamesFilter)")

            val result = Json.obj(
              "server" -> mcpServerRow.name.asJson,
              "serverUuid" -> mcpServerRow.uuid.toString.asJson,
              "tools" -> toolsJson.asJson,
              "count" -> toolsJson.size.asJson
            )

            scala.concurrent.Future.successful(Right(result.spaces2))

          case Left(error) =>
            logger.error(s"ListMcpTools: MCP client error: $error")
            val errorJson = Json.obj(
              "error" -> s"MCP client error: $error".asJson
            )
            scala.concurrent.Future.successful(Left(errorJson.spaces2))
        }
      } yield jsonResult

      // Convert Future to Mono
      Mono.fromCompletionStage(futureResult.asJava.toCompletableFuture)
        .flatMap {
          case Right(jsonText) => Mono.just(McpUtils.createTextResult(jsonText))
          case Left(errorJson) => Mono.just(McpUtils.createTextResult(errorJson, isError = true))
        }
        .onErrorResume { error =>
          logger.error(s"ListMcpTools: unexpected error: ${error.getMessage}", error)
          val errorJson = Json.obj(
            "error" -> error.getMessage.asJson
          )
          Mono.just(McpUtils.createTextResult(errorJson.spaces2, isError = true))
        }
    }
  }
}

object ListToolsForMcpServer {
  /**
   * Create a ListMcpTools instance.
   *
   * @param userId The user identifier
   * @param tenant The tenant identifier (default: "default")
   * @param system The actor system
   * @param sharding The cluster sharding
   * @param ec The execution context
   * @param db The database instance
   * @return A new ListMcpTools instance
   */
  def apply(
    userId: String,
    tenant: String = "default"
  )(implicit
    system: ActorSystem[?],
    sharding: ClusterSharding,
    ec: ExecutionContext,
    db: Database
  ): ListToolsForMcpServer = {
    new ListToolsForMcpServer(userId, tenant)
  }
}
