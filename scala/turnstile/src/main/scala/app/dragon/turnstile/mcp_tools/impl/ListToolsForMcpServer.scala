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
import io.circe.jackson.jacksonToCirce
import io.circe.syntax.*
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import reactor.core.publisher.Mono
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.{Failure, Success, Try}

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
 * @param userId   The user identifier
 * @param tenant   The tenant identifier (default: "default")
 * @param system   The actor system (implicit)
 * @param sharding The cluster sharding (implicit)
 * @param ec       The execution context (implicit)
 * @param db       The database instance (implicit)
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
   * Validate a regex pattern by attempting to compile it.
   * Returns None if valid, Some(error message) if invalid.
   */
  private def validateRegexPattern(pattern: String): Option[String] = {
    if (pattern.startsWith("regex:")) {
      val regexPattern = pattern.stripPrefix("regex:")
      Try(regexPattern.r) match {
        case Failure(e) => Some(e.getMessage)
        case Success(_) => None
      }
    } else {
      None // Non-regex patterns are always valid
    }
  }

  /**
   * Match a tool name against a pattern.
   * Supports exact matching and regex patterns with 'regex:' prefix.
   *
   * @param toolName The tool name to match
   * @param pattern  The pattern (exact string or regex with prefix)
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

  /**
   * Filter a sequence of MCP tools by the provided pattern set.
   * Supports the wildcard "*" to return all tools, exact matches, and regex
   * patterns prefixed with "regex:". Any regex failures during matching are
   * logged and treated as non-matches so they don't break the entire request.
   */
  private def filterToolsByPatterns(tools: Seq[McpSchema.Tool], patterns: Set[String]): Seq[McpSchema.Tool] = {
    if (patterns.contains("*")) {
      tools
    } else {
      tools.filter { tool =>
        patterns.exists { pattern =>
          matchPattern(tool.name(), pattern) match {
            case Success(matched) => matched
            case Failure(e) =>
              logger.warn(s"ListMcpTools: pattern match failure for pattern '$pattern' on tool '${tool.name()}': ${e.getMessage}")
              false
          }
        }
      }
    }
  }

  // New helper: build the JSON result for the tools list
  private def buildToolsResultJson(serverName: String, serverUuid: String, tools: Seq[McpSchema.Tool]): Json = {
    val toolsJson = tools.map { tool =>
      Json.obj(
        "name" -> tool.name().asJson,
        "description" -> Option(tool.description()).asJson,
        "inputSchema" -> jacksonToCirce(
          McpJsonMapper.getDefault.convertValue(tool.inputSchema(), classOf[JsonNode])
        )
      )
    }

    Json.obj(
      "server" -> serverName.asJson,
      "serverUuid" -> serverUuid.asJson,
      "tools" -> toolsJson.asJson,
      "count" -> toolsJson.size.asJson
    )
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
      null // definitions
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
      val mcpServerUuidStr = McpUtils.getStringArg(request, "mcpServerUuid")
      val mcpServerUuid = UUID.fromString(mcpServerUuidStr)

      // Extract toolNames parameter, defaulting to Set("*") if not provided
      val toolNamesFilter = Option(request.arguments())
        .flatMap(args => Option(args.get("toolNames")))
        .map {
          case list: java.util.List[?] => list.asScala.map(_.toString).toSet
          case _ => Set("*")
        }
        .getOrElse(Set("*"))

      logger.debug(s"ListMcpTools: listing tools from server UUID '$mcpServerUuid' with filter: $toolNamesFilter")

      // Validate all regex patterns upfront
      val invalidPatterns = toolNamesFilter.filterNot(_ == "*").flatMap { pattern =>
        validateRegexPattern(pattern).map(errorMsg => (pattern, errorMsg))
      }

      if (invalidPatterns.nonEmpty) {
        val errorMessages = invalidPatterns.map { case (pattern, msg) =>
          s"'$pattern': $msg"
        }.mkString(", ")
        val errorJson = Json.obj(
          "error" -> s"Invalid regex pattern(s): $errorMessages".asJson
        )
        logger.error(s"ListMcpTools: invalid regex patterns provided: $errorMessages")
        Mono.just(McpUtils.createTextResult(errorJson.spaces2, isError = true))
      } else {

        // Query the MCP server for tools
        val futureResult = for {
          // Step 1: Look up the MCP server by UUID
          dbResult <- DbInterface.findMcpServerByUuid(tenant, userId, mcpServerUuid)
          mcpServerRow <- dbResult match {
            case Right(row) =>
              logger.debug(s"ListMcpTools: found server '${row.name}' with UUID ${row.uuid}")
              Future.successful(row)
            case Left(DbNotFound) =>
              logger.error(s"ListMcpTools: server with UUID '$mcpServerUuid' not found")
              Future.failed(new RuntimeException(s"MCP server with UUID '$mcpServerUuid' not found"))
            case Left(error) =>
              logger.error(s"ListMcpTools: database error: ${error.message}")
              Future.failed(new RuntimeException(s"Database error: ${error.message}"))
          }

          // Step 2: Request the list of tools
          actorResult <- ActorLookup.getMcpClientActor(userId, mcpServerRow.uuid.toString)
            .ask[Either[McpClientActor.McpClientError, McpSchema.ListToolsResult]](
            replyTo => McpClientActor.McpListTools(replyTo)
          )

          // Step 3: Process the result and convert to JSON
          callToolResult <- actorResult match {
            case Right(listResult) =>
              logger.debug(s"ListMcpTools: successfully listed ${listResult.tools().size()} tools from server '${mcpServerRow.name}' (UUID: $mcpServerUuid)")

              // Apply filter: if toolNamesFilter contains "*", return all tools; otherwise filter by pattern
              val filteredTools = filterToolsByPatterns(listResult.tools().asScala.toSeq, toolNamesFilter)

              // Build JSON for filtered tools using helper
              val resultJson = buildToolsResultJson(mcpServerRow.name, mcpServerRow.uuid.toString, filteredTools)

              logger.debug(s"ListMcpTools: returning ${filteredTools.size} tools after filtering (filter: $toolNamesFilter)")

              Future.successful(McpUtils.createTextResult(resultJson.spaces2, isError = false))

            case Left(error) =>
              logger.error(s"ListMcpTools: MCP client error: $error")
              val errorJson = Json.obj(
                "error" -> s"MCP client error: $error".asJson
              )
              Future.successful(McpUtils.createTextResult(errorJson.spaces2, isError = true))
          }

        } yield callToolResult

        // Convert Future to Mono
        Mono.fromCompletionStage(futureResult.asJava.toCompletableFuture)

      }
    }
  }
}

object ListToolsForMcpServer {
  /**
   * Create a ListToolsForMcpServer instance.
   *
   * @param userId   The user identifier
   * @param tenant   The tenant identifier (default: "default")
   * @param system   The actor system
   * @param sharding The cluster sharding
   * @param ec       The execution context
   * @param db       The database instance
   * @return A new ListToolsForMcpServer instance
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
