package app.dragon.turnstile.service

import app.dragon.turnstile.actor.{ActorLookup, McpClientActor}
import app.dragon.turnstile.service.tools.{EchoTool, NamespacedTool, StreamingDemoTool, SystemInfoTool}
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.{McpAsyncServerExchange, McpServerFeatures}
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
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


class ToolsService(val userId: String)(implicit ec: ExecutionContext) {
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

  /**
   * Get namespaced tools from an MCP client actor.
   *
   * This method queries a specific MCP client actor for its available tools
   * and returns them as NamespacedTool instances that proxy calls to the remote server.
   *
   * @param mcpClientActorId The ID of the MCP client actor
   * @param system The actor system (implicit)
   * @param timeout The timeout for the actor query (implicit, default 30 seconds)
   * @return A Future containing either an error or a list of namespaced tools
   */
  private[service] def getDownstreamTools(
    mcpClientActorId: String
  )(implicit
    system: ActorSystem[?],
    timeout: Timeout = 30.seconds
  ): Future[Either[McpClientActor.McpClientError, List[McpTool]]] = {
    require(mcpClientActorId.nonEmpty, "mcpClientActorId cannot be empty")

    implicit val sharding: ClusterSharding = ClusterSharding(system)
    
    logger.info(s"Fetching namespaced tools from MCP client actor: $mcpClientActorId for user=$userId")

    // Get the MCP client actor reference
    val clientActor = ActorLookup.getMcpClientActor(mcpClientActorId)

    // Query the actor for its tools
    clientActor.ask[Either[McpClientActor.McpClientError, McpSchema.ListToolsResult]](
      replyTo => McpClientActor.McpListTools(replyTo)
    ).map {
      case Right(listResult) =>
        val tools = listResult.tools().asScala.toList
        logger.info(s"Received ${tools.size} tools from MCP client actor $mcpClientActorId for user=$userId")

        // Convert each tool schema to a NamespacedTool
        val downstreamTools = tools.map { toolSchema =>
          NamespacedTool(toolSchema, mcpClientActorId)
        }

        Right(downstreamTools)

      case Left(error) =>
        logger.error(s"Failed to fetch tools from MCP client actor $mcpClientActorId for user=$userId: $error")
        Left(error)
    }
  }

  def getDefaultToolsSpec: List[McpServerFeatures.AsyncToolSpecification] = {
    val defaultTools = getDefaultTools
    convertToAsyncToolSpec(defaultTools)
  }
  
  def getDownstreamToolsSpec(
    mcpClientActorId: String
  )(implicit
    system: ActorSystem[?],
    timeout: Timeout = 30.seconds
  ): Future[Either[McpClientActor.McpClientError, List[McpServerFeatures.AsyncToolSpecification]]] = {
    require(mcpClientActorId.nonEmpty, "mcpClientActorId cannot be empty")

    getDownstreamTools(mcpClientActorId).map {
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
      McpServerFeatures.AsyncToolSpecification.builder()
        .tool(tool.getSchema())
        .callHandler(tool.getAsyncHandler().asJava)
        .build()
    }
  }
}
