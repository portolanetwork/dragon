package app.dragon.turnstile.service

import app.dragon.turnstile.utils.JsonUtils
import dragon.turnstile.api.v1.{CreateToolRequest, ListToolsRequest, Tool, ToolList, TurnstileService}
import org.apache.pekko.actor.typed.ActorSystem
import com.google.protobuf.timestamp.Timestamp
import org.slf4j.{Logger, LoggerFactory}
import io.grpc.Status

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}

/**
 * Companion object for TurnstileServiceImpl.
 */
object TurnstileServiceImpl {
  sealed trait TurnstileServiceError {
    def message: String
  }

  object TurnstileServiceError {
    case class ValidationError(message: String) extends TurnstileServiceError
    case class AlreadyExistsError(message: String) extends TurnstileServiceError
    case class InternalError(message: String) extends TurnstileServiceError

    def fromToolsServiceError(error: ToolsService.ToolsServiceError): TurnstileServiceError = {
      InternalError(error.message)
    }
  }
}

/**
 * Implementation of the TurnstileService gRPC service.
 *
 * This service provides access to user-specific tools through gRPC,
 * leveraging the existing ToolsService for tool management.
 */
class TurnstileServiceImpl(system: ActorSystem[_]) extends TurnstileService {
  import TurnstileServiceImpl._

  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileServiceImpl])
  private implicit val sys: ActorSystem[_] = system
  private implicit val ec: ExecutionContext = system.executionContext

  // Get the singleton ToolsService instance
  private val toolsService = ToolsService.instance

  /**
   * List all tools for a user with pagination support.
   *
   * This method retrieves both default and custom tools for the specified user
   * from the ToolsService and converts them to the gRPC Tool format.
   *
   * @param request ListToolsRequest containing the userId and pagination parameters
   * @return Future[ToolList] containing tools for the user and next page token
   */
  override def listTools(
    request: ListToolsRequest
  ): Future[ToolList] = {
    logger.info(s"Received ListTools request for userId: ${request.userId}")

    // Validate request
    if (request.userId.isEmpty) {
      logger.warn("ListTools request with empty userId")
      return Future.failed(Status.INVALID_ARGUMENT.withDescription("userId cannot be empty").asRuntimeException())
    }

    toolsService.getTools(request.userId).flatMap {
      case Right(dynamicTools) =>
        // Convert DynamicTool to proto Tool
        val protoTools = dynamicTools.map { dynamicTool =>
          convertToProtoTool(request.userId, dynamicTool)
        }

        logger.info(s"Returning ${protoTools.size} tools for userId: ${request.userId} " +
          s"(${dynamicTools.count(_.isDefault)} default, ${dynamicTools.count(!_.isDefault)} custom)")

        // Note: Pagination not implemented yet - returning all tools
        Future.successful(ToolList(
          tools = protoTools,
          nextPageToken = ""
        ))
      case Left(error) =>
        logger.error(s"Failed to get tools for user ${request.userId}: ${error.message}")
        Future.failed(Status.INTERNAL.withDescription(error.message).asRuntimeException())
    }
  }

  /**
   * Create a new custom tool for a user.
   *
   * This method creates a new custom tool from the request, validates it,
   * and registers it with the ToolsService for the specified user.
   * Errors are returned as gRPC status codes.
   *
   * @param request CreateToolRequest containing userId, tool_name, description, and schema_json
   * @return Future[Tool] containing the created Tool, or a failed Future with gRPC status
   */
  override def createTool(
    request: CreateToolRequest
  ): Future[Tool] = {
    logger.info(s"Received CreateTool request for userId: ${request.userId}, toolName: ${request.toolName}")

    createToolInternal(request) match {
      case Right(dynamicToolFuture) =>
        dynamicToolFuture.flatMap {
          case Right(createdTool) =>
            logger.info(s"Successfully created tool ${request.toolName} with id ${createdTool.id} for user ${request.userId}")
            Future.successful(convertToProtoTool(request.userId, createdTool))
          case Left(error) =>
            logger.warn(s"CreateTool failed: ${error.message}")
            Future.failed(errorToGrpcException(TurnstileServiceError.fromToolsServiceError(error)))
        }
      case Left(error) =>
        logger.warn(s"CreateTool validation failed: ${error.message}")
        Future.failed(errorToGrpcException(error))
    }
  }

  /**
   * Internal method to create a tool using Either for error handling.
   */
  private def createToolInternal(
    request: CreateToolRequest
  ): Either[TurnstileServiceError, Future[Either[ToolsService.ToolsServiceError, DynamicTool]]] = {
    for {
      _ <- validateCreateToolRequest(request)
      schemaJson <- convertSchemaToJson(request.schemaJson)
      dynamicTool <- createDynamicTool(request, schemaJson)
      _ <- validateDynamicTool(dynamicTool)
    } yield toolsService.createTool(request.userId, dynamicTool)
  }

  private def validateCreateToolRequest(
    request: CreateToolRequest
  ): Either[TurnstileServiceError, Unit] = {
    if (request.userId.isEmpty) {
      Left(TurnstileServiceError.ValidationError("userId cannot be empty"))
    } else if (request.toolName.isEmpty) {
      Left(TurnstileServiceError.ValidationError("tool_name cannot be empty"))
    } else if (request.description.isEmpty) {
      Left(TurnstileServiceError.ValidationError("description cannot be empty"))
    } else {
      Right(())
    }
  }

  private def convertSchemaToJson(
    schemaStruct: Option[com.google.protobuf.struct.Struct]
  ): Either[TurnstileServiceError, String] = {
    schemaStruct match {
      case Some(struct) =>
        JsonUtils.structToJsonString(struct) match {
          case Success(json) => Right(json)
          case Failure(ex) =>
            Left(TurnstileServiceError.ValidationError(s"Failed to convert schema to JSON: ${ex.getMessage}"))
        }
      case None =>
        Left(TurnstileServiceError.ValidationError("schema_json is required"))
    }
  }

  private def createDynamicTool(
    request: CreateToolRequest,
    schemaJson: String
  ): Either[TurnstileServiceError, DynamicTool] = {
    Try {
      DynamicTool(
        name = request.toolName,
        description = request.description,
        schemaJson = schemaJson,
        handler = DynamicTool.echoArgsHandler,
        isDefault = false
      )
    }.toEither.left.map(ex =>
      TurnstileServiceError.InternalError(s"Failed to create DynamicTool: ${ex.getMessage}")
    )
  }

  private def validateDynamicTool(
    tool: DynamicTool
  ): Either[TurnstileServiceError, Unit] = {
    DynamicTool.validate(tool)
      .left.map(error => TurnstileServiceError.ValidationError(error))
      .map(_ => ())
  }

  private def errorToGrpcException(
    error: TurnstileServiceError
  ): Throwable = {
    error match {
      case TurnstileServiceError.ValidationError(msg) =>
        Status.INVALID_ARGUMENT.withDescription(msg).asRuntimeException()
      case TurnstileServiceError.AlreadyExistsError(msg) =>
        Status.ALREADY_EXISTS.withDescription(msg).asRuntimeException()
      case TurnstileServiceError.InternalError(msg) =>
        Status.INTERNAL.withDescription(msg).asRuntimeException()
    }
  }

  /**
   * Convert a DynamicTool to a proto Tool message.
   *
   * Generates a deterministic UUID based on userId and tool name,
   * and uses current timestamp for created_time and updated_time.
   *
   * @param userId The user ID (for UUID generation)
   * @param dynamicTool The DynamicTool to convert
   * @return proto Tool message
   */
  private def convertToProtoTool(
    userId: String,
    dynamicTool: DynamicTool
  ): Tool = {
    // Generate deterministic UUID from userId + toolName
    val toolUuid = UUID.nameUUIDFromBytes(s"$userId:${dynamicTool.name}".getBytes("UTF-8")).toString

    // Get current timestamp
    val now = System.currentTimeMillis() / 1000
    val timestamp = Timestamp(seconds = now, nanos = 0)

    // Convert JSON string to protobuf Struct
    val schemaStruct = JsonUtils.jsonStringToStruct(dynamicTool.schemaJson) match {
      case Success(struct) => Some(struct)
      case Failure(ex) =>
        logger.warn(s"Failed to parse schema JSON for tool ${dynamicTool.name}: ${ex.getMessage}")
        None
    }

    Tool(
      toolUuid = toolUuid,
      toolName = dynamicTool.name,
      description = dynamicTool.description,
      schemaJson = schemaStruct,
      createdTime = Some(timestamp),
      updatedTime = Some(timestamp)
    )
  }

}
