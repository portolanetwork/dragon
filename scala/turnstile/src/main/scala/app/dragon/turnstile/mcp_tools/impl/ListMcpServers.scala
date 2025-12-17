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

import app.dragon.turnstile.db.DbInterface
import app.dragon.turnstile.mcp_tools.{AsyncToolHandler, McpTool, McpUtils}
import io.circe.Json
import io.circe.syntax.*
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters.*

/**
 * List MCP servers tool - returns a list of registered MCP servers for the user as JSON.
 *
 * This tool provides information about all MCP servers configured for the user,
 * including:
 * - Server name
 * - Server URL
 * - Server UUID (for reference)
 * - Authentication type and OAuth configuration status
 * - Transport type
 * - Timestamps
 *
 * This is useful for:
 * - Discovering available MCP servers
 * - Checking server configuration
 * - Getting server identifiers for other operations
 *
 * Example usage:
 * {{{
 * {
 *   "name": "list_mcp_servers",
 *   "arguments": {}
 * }
 * }}}
 *
 * Returns JSON:
 * {{{
 * {
 *   "servers": [
 *     {
 *       "name": "my-server",
 *       "url": "https://example.com",
 *       "uuid": "12345678-1234-1234-1234-123456789abc",
 *       "authType": "discover",
 *       "transportType": "streaming_http",
 *       "hasClientId": true,
 *       "hasTokenEndpoint": true,
 *       "createdAt": 1234567890000,
 *       "updatedAt": 1234567890000
 *     }
 *   ],
 *   "count": 1
 * }
 * }}}
 *
 * Note: Sensitive fields (clientSecret, refreshToken, staticToken) are excluded from the response.
 *
 * @param userId The user identifier
 * @param tenant The tenant identifier (default: "default")
 * @param db The database instance (implicit)
 * @param ec The execution context (implicit)
 */
class ListMcpServers(
  userId: String,
  tenant: String = "default"
)(
  implicit db: Database,
  ec: ExecutionContext
) extends McpTool {

  override def getSchema(): McpSchema.Tool = {
    McpUtils.createToolSchemaBuilder(
      name = "list_mcp_servers",
      description = "Lists all registered MCP servers for the user"
    )
      .inputSchema(McpUtils.createObjectSchema()) // No arguments required
      .build()
  }

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request) => {
      logger.debug(s"ListMcpServers: listing servers for user '$userId' in tenant '$tenant'")

      // Query the database for MCP servers
      val futureResult = DbInterface.listMcpServers(tenant, userId).map {
        case Right(servers) =>
          logger.debug(s"ListMcpServers: found ${servers.size} servers")

          // Convert servers to JSON, excluding sensitive fields
          val serversJson = servers.map { server =>
            Json.obj(
              "name" -> server.name.asJson,
              "url" -> server.url.asJson,
              "uuid" -> server.uuid.toString.asJson,
              "authType" -> server.authType.asJson,
              "transportType" -> server.transportType.asJson,
              "hasClientId" -> server.clientId.isDefined.asJson,
              "hasTokenEndpoint" -> server.tokenEndpoint.isDefined.asJson,
              "createdAt" -> server.createdAt.getTime.asJson,
              "updatedAt" -> server.updatedAt.getTime.asJson
            )
          }

          val result = Json.obj(
            "servers" -> serversJson.asJson,
            "count" -> servers.size.asJson
          )

          Right(result.spaces2)

        case Left(error) =>
          logger.error(s"ListMcpServers: database error: ${error.message}")
          val errorJson = Json.obj(
            "error" -> error.message.asJson
          )
          Left(errorJson.spaces2)
      }

      // Convert Future to Mono
      Mono.fromCompletionStage(futureResult.asJava.toCompletableFuture)
        .map {
          case Right(jsonText) => McpUtils.createTextResult(jsonText)
          case Left(errorJson) => McpUtils.createTextResult(errorJson, isError = true)
        }
    }
  }
}

object ListMcpServers {
  /**
   * Create a ListMcpServers instance.
   *
   * @param userId The user identifier
   * @param tenant The tenant identifier (default: "default")
   * @param db The database instance
   * @param ec The execution context
   * @return A new ListMcpServers instance
   */
  def apply(
    userId: String,
    tenant: String = "default"
  )(implicit
    db: Database,
    ec: ExecutionContext
  ): ListMcpServers = {
    new ListMcpServers(userId, tenant)
  }
}
