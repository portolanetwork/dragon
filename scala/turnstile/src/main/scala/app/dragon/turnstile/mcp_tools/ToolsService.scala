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

package app.dragon.turnstile.mcp_tools

import app.dragon.turnstile.db.{DbInterface, McpServerRow}
import app.dragon.turnstile.mcp_client.McpClientActor
import app.dragon.turnstile.mcp_tools.impl.{EchoTool, NamespacedTool, StreamingDemoTool, SystemInfoTool}
import app.dragon.turnstile.utils.ActorLookup
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.{McpAsyncServerExchange, McpServerFeatures}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.JdbcBackend.Database

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FunctionConverters.enrichAsJavaBiFunction

/**
 * Type alias for MCP tool handler functions
 */
 type SyncToolHandler = (McpTransportContext, McpSchema.CallToolRequest) => McpSchema.CallToolResult
 type AsyncToolHandler = (McpAsyncServerExchange, McpSchema.CallToolRequest) => reactor.core.publisher.Mono[McpSchema.CallToolResult]

/**
 * Service for managing MCP tools.
 *
 * This service provides access to default (built-in) MCP tools
 * and handlers for use with MCP servers.
 *
 * Key features:
 * - Default (built-in) tools available to all users
 * - Thread-safe concurrent access
 * - Java BiFunction handlers for MCP SDK compatibility
 *
 * Example usage:
 * {{{
 * // Get a user-scoped ToolsService
 * val toolsService = ToolsService.getForUser("user123")
 *
 * // Get all tools for that user
 * val tools = toolsService.getDefaultTools()
 *
 * // Get tools with handlers for MCP server integration
 * val toolsWithHandlers = toolsService.getDefaultToolsSpec
 * }}}
 */


object ToolsService {

  // Per-user cache of ToolsService instances
  private val instances: ConcurrentHashMap[String, ToolsService] = new ConcurrentHashMap()

  /**
   * Get or create a cached ToolsService for the given userId.
   * This is the recommended entry point for obtaining a user-scoped service.
   */
  def getInstance(userId: String)(implicit ec: ExecutionContext): ToolsService = {
    require(userId != null && userId.nonEmpty, "userId cannot be empty")
    // use SAM conversion for java.util.function.Function
    instances.computeIfAbsent(userId, (id: String) => new ToolsService(id))
  }
}


class ToolsService(
  val userId: String
)(implicit ec: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[ToolsService])
  // Default tools (built-in)
  private val defaultTools: List[McpTool] = List(
    EchoTool("echo1"),
    EchoTool("echo2"),
    SystemInfoTool,
    StreamingDemoTool
  )

  logger.info(s"ToolsService initialized for user=$userId with ${defaultTools.size} default tools: ${defaultTools.map(_.getName()).mkString(", ")}")

  /**
   * Get all tools for a user.
   * Currently returns default tools for all users.
   *
   * @return List of McpTool instances
   */
  private def getDefaultTools: List[McpTool] = {
    defaultTools
  }

  def getDefaultToolsSpec: List[McpServerFeatures.AsyncToolSpecification] = {
    val defaultTools = getDefaultTools
    convertToAsyncToolSpec(defaultTools)
  }

  def getAllDownstreamToolsSpec(
    tenant: String = "default"
  )(implicit
    system: ActorSystem[?],
    timeout: Timeout = 30.seconds,
    db: Database
  ): Future[Either[McpClientActor.McpClientError, List[McpServerFeatures.AsyncToolSpecification]]] = {

    logger.info(s"Fetching all downstream tools for user=$userId, tenant=$tenant")

    // Fetch all MCP servers for this user from the database and fetch tools for each
    DbInterface.listMcpServers(tenant, userId).flatMap {
      case Right(servers) =>
        logger.info(s"Found ${servers.size} MCP servers for user=$userId, fetching tools from each")

        // Sequence per-server futures and collect successful tool specs
        val toolsFutures: Seq[Future[Either[McpClientActor.McpClientError, List[McpServerFeatures.AsyncToolSpecification]]]] = {
          servers.map(server => getDownstreamToolsSpec(server))
        }

        Future.sequence(toolsFutures).map { results =>
          val allTools = results.collect { case Right(tools) => tools }.flatten.toList
          logger.info(s"Successfully fetched ${allTools.size} total tools from ${servers.size} MCP servers for user=$userId")
          Right(allTools)
        }

      case Left(dbError) =>
        logger.error(s"Failed to fetch MCP servers from database for user=$userId, tenant=$tenant: $dbError")
        Future.successful(Left(McpClientActor.ProcessingError(s"Database error: ${dbError}")))
    }.recover {
      case ex: Exception =>
        logger.error(s"Failed to fetch MCP servers from database for user=$userId, tenant=$tenant", ex)
        Left(McpClientActor.ProcessingError(s"Database error: ${ex.getMessage}"))
    }
  }

  /**
   * Get namespaced tools from an MCP client actor.
   *
   * This method queries a specific MCP client actor for its available tools
   * and returns them as NamespacedTool instances that proxy calls to the remote server.
   *
   * @param mcpServerUuid The ID of the MCP client actor
   * @param system The actor system (implicit)
   * @param timeout The timeout for the actor query (implicit, default 30 seconds)
   * @return A Future containing either an error or a list of namespaced tools
   */
  private[mcp_tools] def getDownstreamTools(
    mcpServerRow: McpServerRow
  )(implicit
    system: ActorSystem[?],
    timeout: Timeout = 30.seconds
  ): Future[Either[McpClientActor.McpClientError, List[McpTool]]] = {
    implicit val sharding: ClusterSharding = ClusterSharding(system)
    
    logger.info(s"Fetching namespaced tools from MCP client actor: ${mcpServerRow.uuid} (${mcpServerRow.name}) for user=$userId")

    ActorLookup.getMcpClientActor(userId, mcpServerRow.uuid.toString) ! McpClientActor.Initialize(mcpServerRow)

    // Query the actor for its tools
    ActorLookup.getMcpClientActor(userId, mcpServerRow.uuid.toString)
      .ask[Either[McpClientActor.McpClientError, McpSchema.ListToolsResult]](
      replyTo => McpClientActor.McpListTools(replyTo)
    ).map {
      case Right(listResult) =>
        val tools = listResult.tools().asScala.toList

        logger.info(s"Received ${tools.size} tools from MCP client actor ${mcpServerRow.uuid.toString} for user=$userId")

        // Convert each tool schema to a NamespacedTool
        val downstreamTools = tools.map { downstreamToolSchema =>
          val turnstileToolSchema = McpSchema.Tool.builder()
            .name(s"${mcpServerRow.name}.${downstreamToolSchema.name()}")
            .description(downstreamToolSchema.description())
            .inputSchema(downstreamToolSchema.inputSchema())
            .outputSchema(downstreamToolSchema.outputSchema())
            .annotations(downstreamToolSchema.annotations())
            .build()

          NamespacedTool(turnstileToolSchema, downstreamToolSchema, userId, mcpServerRow.uuid.toString)
        }

        Right(downstreamTools)

      case Left(error) =>
        logger.error(s"Failed to fetch tools from MCP client actor ${mcpServerRow.uuid.toString} for user=$userId: $error")
        Left(error)
    }
  }

  private def getDownstreamToolsSpec(
    mcpServerRow: McpServerRow
  )(implicit
    system: ActorSystem[?],
    timeout: Timeout = 30.seconds
  ): Future[Either[McpClientActor.McpClientError, List[McpServerFeatures.AsyncToolSpecification]]] = {
    getDownstreamTools(mcpServerRow).map {
      case Right(tools) =>
        val toolSpecs = convertToAsyncToolSpec(tools)
        Right(toolSpecs)
      case Left(error) =>
        Left(error)
    }
  }

  /**
   * Convert a list of McpTools to AsyncToolSpecification instances.
   *
   * @param tools The list of McpTool instances
   * @return List of AsyncToolSpecification instances
   */
  private def convertToAsyncToolSpec(
    tools: List[McpTool]
  ): List[McpServerFeatures.AsyncToolSpecification] = {
    tools.map { tool =>
      // Log schema
      logger.debug(s"Converting tool to AsyncToolSpecification: ${tool.getName()} with schema: ${tool.getSchema()}")

      McpServerFeatures.AsyncToolSpecification.builder()
        .tool(tool.getSchema())
        .callHandler(tool.getAsyncHandler().asJava)
        .build()
    }
  }
}
