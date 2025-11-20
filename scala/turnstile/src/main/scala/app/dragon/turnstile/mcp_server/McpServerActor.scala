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

package app.dragon.turnstile.mcp_server

import app.dragon.turnstile.mcp_server.{McpStreamingHttpServer, PekkoToSpringRequestAdapter, SpringToPekkoResponseAdapter}
import app.dragon.turnstile.mcp_tools.ToolsService
import app.dragon.turnstile.serializer.TurnstileSerializable
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import reactor.core.scheduler.Schedulers
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.Future
import scala.jdk.FutureConverters.*

/**
 * Entity ID for McpServerActor instances.
 *
 * Format: {userId}-{mcpServerActorId}
 * This allows for user-scoped MCP server isolation within the cluster.
 *
 * @param userId The user identifier
 * @param mcpServerActorId The unique actor identifier for this user's MCP server
 */
case class McpServerActorId(userId: String, mcpServerActorId: String = "singleton") {
  override def toString: String = s"$userId.$mcpServerActorId"
}

object McpServerActorId {
  /**
   * Parse an entity ID string into a McpServerActorId.
   *
   * @param entityId The entity ID string in format "userId-mcpServerActorId"
   * @return McpServerActorId instance
   */
  def fromString(
    entityId: String
  ): McpServerActorId = {
    val Array(userId, mcpServerActorId) = entityId.split("\\.", 2)
    McpServerActorId(userId, mcpServerActorId)
  }
}

/**
 * MCP Server Actor - manages a user-scoped MCP server instance.
 *
 * This actor encapsulates a TurnstileStreamingHttpMcpServer (Spring WebFlux-based MCP server)
 * and handles HTTP requests from the gateway by adapting between Pekko and Spring types.
 *
 * Architecture:
 * - Each user can have their own MCP server instance(s)
 * - Cluster sharding ensures actors are distributed across nodes
 * - Actor isolates user state and provides session affinity
 * - Adapters bridge Pekko HTTP ↔ Spring WebFlux
 *
 * Lifecycle States:
 * 1. initState: Waiting for MCP server initialization and downstream tool refresh
 *    - Stashes incoming requests
 *    - Fetches tools from registered downstream MCP servers
 * 2. activeState: Handling HTTP requests
 *    - Processes GET, POST, DELETE requests
 *    - Adapts Pekko requests to Spring WebFlux
 *    - Converts Spring responses back to Pekko
 *
 * Message Flow:
 * {{{
 * Gateway → McpServerActor.McpPostRequest(pekkoRequest)
 *   ↓
 * PekkoToSpringRequestAdapter (converts HttpRequest → ServerHttpRequest)
 *   ↓
 * TurnstileStreamingHttpMcpServer.getHttpHandler (Spring WebFlux)
 *   ↓
 * SpringToPekkoResponseAdapter (converts ServerHttpResponse → HttpResponse)
 *   ↓
 * Reply to Gateway with HttpResponse
 * }}}
 *
 * Failure Handling:
 * - Initialization failure → transitions to active state anyway (graceful degradation)
 * - Request processing errors → returns Left(ProcessingError) to caller
 * - PostStop signal → stops embedded MCP server cleanly
 */
object McpServerActor {
  val TypeKey: EntityTypeKey[McpServerActor.Message] =
    EntityTypeKey[McpServerActor.Message]("McpServerActor")

  def initSharding(
    system: ActorSystem[?],
    db: Database,
  ): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val mcpServerActorId = McpServerActorId.fromString(entityContext.entityId)

      McpServerActor(mcpServerActorId, db)
    })

  sealed trait Message extends TurnstileSerializable

  final case class McpGetRequest(request: HttpRequest,
    replyTo: ActorRef[Either[McpActorError, HttpResponse]])
    extends Message

  final case class McpPostRequest(
    request: HttpRequest,
    replyTo: ActorRef[Either[McpActorError, HttpResponse]])
    extends Message

  final case class McpDeleteRequest(
    request: HttpRequest,
    replyTo: ActorRef[Either[McpActorError, HttpResponse]])
    extends Message

  final case class AddTools(
    toolSpecSeq: Seq[AsyncToolSpecification]
  ) extends Message

  // Remove WrappedGetResponse, use only WrappedHttpResponse for all handlers
  private final case class WrappedHttpResponse(
    result: scala.util.Try[HttpResponse],
    replyTo: ActorRef[Either[McpActorError, HttpResponse]]
  ) extends Message

  sealed trait McpActorError extends TurnstileSerializable
  final case class ProcessingError(message: String) extends McpActorError

  // Internal message for server initialization
  private final case class DownstreamRefreshStatus(status: Either[McpActorError, Seq[AsyncToolSpecification]]) extends Message

  def apply(
    mcpServerActorId: McpServerActorId,
    db: Database,
  ): Behavior[Message] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

        context.log.info(s"Starting MCP server for user ${mcpServerActorId.userId}, actor ${mcpServerActorId.mcpServerActorId}")

        // Start the MCP server asynchronously
        val mcpServer = McpStreamingHttpServer(mcpServerActorId.userId).start()

        new McpServerActor(context, buffer, mcpServerActorId, db).initState(mcpServer)
      }
    }
  }
}

class McpServerActor(
  context: ActorContext[McpServerActor.Message],
  buffer: StashBuffer[McpServerActor.Message],
  mcpServerActorId: McpServerActorId,
  db: Database,
) {
  import McpServerActor.*
  // Provide required implicits for adapters
  implicit val system: ActorSystem[Nothing] = context.system
  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext
  implicit val database: Database = db

  /**
   * State while waiting for the MCP server to start.
   * Stashes incoming requests until the server is ready.
   */
  def initState(
    turnstileMcpServer: McpStreamingHttpServer
  ): Behavior[Message] = {

    // Add default tools from ToolsService
    ToolsService.getInstance(mcpServerActorId.userId).getDefaultToolsSpec().foreach { toolSpec =>
      context.log.info(s"Adding default tool to MCP server for actor $mcpServerActorId: ${toolSpec.tool().name()}")
      turnstileMcpServer.addTool(toolSpec)
    }

    // Pipe the result to self
    context.pipeToSelf(ToolsService.getInstance(mcpServerActorId.userId).getAllDownstreamToolsSpec()) {//turnstileMcpServer.refreshDownstreamTools()) {
      case scala.util.Success(Right(toolSpecSeq)) => DownstreamRefreshStatus(Right((toolSpecSeq)))
      case scala.util.Success(Left(error)) => DownstreamRefreshStatus(Left(ProcessingError(error.toString)))
      case scala.util.Failure(error) => DownstreamRefreshStatus(Left(ProcessingError(error.getMessage)))
    }

    Behaviors.receiveMessagePartial(handleDownstreamRefresh(turnstileMcpServer))
  }

  def activeState(
    turnstileMcpServer: McpStreamingHttpServer
  ): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleMcpGetRequest(turnstileMcpServer)
        .orElse(handleMcpPostRequest(turnstileMcpServer))
        .orElse(handleMcpDeleteRequest(turnstileMcpServer))
        .orElse(handleAddTools(turnstileMcpServer))
        .orElse(handleWrappedHttpResponse())
    }.receiveSignal {
      case (_, org.apache.pekko.actor.typed.PostStop) =>
        context.log.info(s"Stopping MCP server for actor $mcpServerActorId on PostStop")
        turnstileMcpServer.stop()
        Behaviors.same
    }
  }

  def handleDownstreamRefresh(
    turnstileMcpServer: McpStreamingHttpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case DownstreamRefreshStatus(Right(toolSpecSeq)) =>
      context.log.info(s"MCP server for actor $mcpServerActorId downstream refresh succeeded, transitioning to active state")
      toolSpecSeq.foreach { toolSpec =>
        context.log.info(s"Adding downstream tool to MCP server for actor $mcpServerActorId: ${toolSpec.tool().name()}")
        turnstileMcpServer.addTool(toolSpec)
      }

      // Unstash all buffered messages and transition to active state
      buffer.unstashAll(activeState(turnstileMcpServer))
    case DownstreamRefreshStatus(Left(_)) =>
      context.log.error(s"MCP server for actor $mcpServerActorId downstream refresh failed, but continuing to active state")
      // Unstash all buffered messages and transition to active state anyway
      buffer.unstashAll(activeState(turnstileMcpServer))
    case other =>
      context.log.debug(s"Stashing message while MCP server is initializing: ${other.getClass.getSimpleName}")
      buffer.stash(other)
      Behaviors.same
  }

  def handleMcpGetRequest(
    turnstileMcpServer: McpStreamingHttpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpGetRequest(request, replyTo) =>
      context.log.info(s"MCP Actor $mcpServerActorId handling GET request")
      context.pipeToSelf(handlePekkoRequest(request, turnstileMcpServer)) {
        case scala.util.Success(response) => WrappedHttpResponse(scala.util.Success(response), replyTo)
        case scala.util.Failure(exception) => WrappedHttpResponse(scala.util.Failure(exception), replyTo)
      }
      Behaviors.same
  }

  def handleMcpPostRequest(
    turnstileMcpServer: McpStreamingHttpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpPostRequest(request, replyTo) =>
      context.log.info(s"Handling MCP POST request for user ${mcpServerActorId.userId}, actor ${mcpServerActorId.mcpServerActorId}: ${request.uri}")

      context.pipeToSelf(handlePekkoRequest(request, turnstileMcpServer)) {
        case scala.util.Success(response) => WrappedHttpResponse(scala.util.Success(response), replyTo)
        case scala.util.Failure(exception) => WrappedHttpResponse(scala.util.Failure(exception), replyTo)
      }
      Behaviors.same
  }

  def handleMcpDeleteRequest(
    turnstileMcpServer: McpStreamingHttpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case McpDeleteRequest(request, replyTo) =>
      context.log.info(s"Handling MCP DELETE request: ${request.uri}")
      context.pipeToSelf(handlePekkoRequest(request, turnstileMcpServer)) {
        case scala.util.Success(response) => WrappedHttpResponse(scala.util.Success(response), replyTo)
        case scala.util.Failure(exception) => WrappedHttpResponse(scala.util.Failure(exception), replyTo)
      }
      Behaviors.same
  }

  def handleWrappedHttpResponse(): PartialFunction[Message, Behavior[Message]] = {
    case WrappedHttpResponse(result, replyTo) =>
      result match {
        case scala.util.Success(response) =>
          replyTo ! Right(response)
        case scala.util.Failure(exception) =>
          replyTo ! Left(ProcessingError(exception.getMessage))
      }
      Behaviors.same
  }

  def handleAddTools(
    turnstileMcpServer: McpStreamingHttpServer
  ): PartialFunction[Message, Behavior[Message]] = {
    case AddTools(toolSpecSeq) =>
      context.log.info(s"Adding ${toolSpecSeq.size} tools to MCP server for actor $mcpServerActorId")

      toolSpecSeq.foreach { toolSpec =>
          context.log.info(s"Adding tool to MCP server for actor $mcpServerActorId: ${toolSpec.tool().name()}")
          turnstileMcpServer.addTool(toolSpec)

      }

      Behaviors.same
  }

  private def handlePekkoRequest(
    request: HttpRequest,
    turnstileMcpServer: McpStreamingHttpServer
  ): Future[HttpResponse] = {
    val springRequest = PekkoToSpringRequestAdapter(request)
    val springResponse = SpringToPekkoResponseAdapter()
    turnstileMcpServer.getHttpHandler match {
      case Some(handler) =>
        val handlerMono = handler.handle(springRequest, springResponse)
        handlerMono
          .subscribeOn(Schedulers.boundedElastic())
          .toFuture.asScala.flatMap { _ =>
            springResponse.getPekkoResponse()
          }
      case None =>
        Future.failed(new IllegalStateException("HttpHandler is not initialized in TurnstileStreamingHttpMcpServer"))
    }
  }
}