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

package app.dragon.turnstile.monitoring

import cats.syntax.functor.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

/**
 * Sealed trait representing event-specific data stored in the raw_data JSONB column.
 *
 * This allows type-safe, extensible event data while maintaining flexibility
 * in the database schema. Each event type can have its own specific fields.
 */
sealed trait EventData

object EventData {
  /**
   * Event data for MCP server requests/responses
   *
   * @param method HTTP method (GET, POST, DELETE)
   * @param uri Request URI
   * @param statusCode Response status code
   * @param errorMessage Optional error message if request failed
   */
  final case class McpRequestData(
    method: String,
    uri: String,
    statusCode: Option[Int] = None,
    errorMessage: Option[String] = None
  ) extends EventData

  /**
   * Event data for client connection events
   *
   * @param clientId Client identifier
   * @param connectionType Connection type (websocket, http, etc.)
   * @param remoteAddress Client remote address
   */
  final case class ClientConnectionData(
    clientId: String,
    connectionType: String,
    remoteAddress: Option[String] = None
  ) extends EventData

  /**
   * Event data for authentication events
   *
   * @param authType Authentication type (oauth, static, none)
   * @param success Whether authentication succeeded
   * @param errorMessage Optional error message if auth failed
   */
  final case class AuthData(
    authType: String,
    success: Boolean,
    errorMessage: Option[String] = None
  ) extends EventData

  /**
   * Event data for tool execution events
   *
   * @param toolName Name of the tool executed
   * @param executionTimeMs Execution time in milliseconds
   * @param success Whether tool execution succeeded
   * @param errorMessage Optional error message if execution failed
   */
  final case class ToolExecutionData(
    toolName: String,
    executionTimeMs: Long,
    success: Boolean,
    errorMessage: Option[String] = None
  ) extends EventData

  /**
   * Generic event data for events that don't fit other categories
   *
   * @param data Map of key-value pairs containing event-specific data
   */
  final case class GenericData(
    data: Map[String, String] = Map.empty
  ) extends EventData

  /**
   * Empty event data for events that don't require additional data
   */
  case object EmptyData extends EventData

  // Circe encoders
  implicit val mcpRequestDataEncoder: Encoder[McpRequestData] = deriveEncoder
  implicit val clientConnectionDataEncoder: Encoder[ClientConnectionData] = deriveEncoder
  implicit val authDataEncoder: Encoder[AuthData] = deriveEncoder
  implicit val toolExecutionDataEncoder: Encoder[ToolExecutionData] = deriveEncoder
  implicit val genericDataEncoder: Encoder[GenericData] = deriveEncoder

  implicit val eventDataEncoder: Encoder[EventData] = Encoder.instance {
    case data: McpRequestData => mcpRequestDataEncoder(data)
    case data: ClientConnectionData => clientConnectionDataEncoder(data)
    case data: AuthData => authDataEncoder(data)
    case data: ToolExecutionData => toolExecutionDataEncoder(data)
    case data: GenericData => genericDataEncoder(data)
    case EmptyData => io.circe.Json.obj()
  }

  // Circe decoders
  implicit val mcpRequestDataDecoder: Decoder[McpRequestData] = deriveDecoder
  implicit val clientConnectionDataDecoder: Decoder[ClientConnectionData] = deriveDecoder
  implicit val authDataDecoder: Decoder[AuthData] = deriveDecoder
  implicit val toolExecutionDataDecoder: Decoder[ToolExecutionData] = deriveDecoder
  implicit val genericDataDecoder: Decoder[GenericData] = deriveDecoder

  // For decoding, we try each type in order
  implicit val eventDataDecoder: Decoder[EventData] =
    mcpRequestDataDecoder.widen[EventData]
      .or(clientConnectionDataDecoder.widen[EventData])
      .or(authDataDecoder.widen[EventData])
      .or(toolExecutionDataDecoder.widen[EventData])
      .or(genericDataDecoder.widen[EventData])
      .or(Decoder.const(EmptyData))
}
