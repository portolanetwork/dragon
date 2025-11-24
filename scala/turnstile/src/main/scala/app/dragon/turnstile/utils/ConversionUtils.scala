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

package app.dragon.turnstile.utils

import app.dragon.turnstile.auth.ClientAuthService
import app.dragon.turnstile.db.{EventLogRow, McpServerRow}
import com.google.protobuf.struct.{Struct, Value}
import dragon.turnstile.api.v1.*

/**
 * Utility object for converting between domain models, database rows, and proto messages.
 */
object ConversionUtils {

  /**
   * Determines the LoginStatus enum value based on auth type and authentication state.
   */
  def determineLoginStatus(
    loginStatusEnum: ClientAuthService.LoginStatusEnum
  ): LoginStatus = {
    loginStatusEnum match {
      case ClientAuthService.LoginStatusEnum.AUTHENTICATED => LoginStatus.LOGIN_STATUS_AUTHENTICATED
      case ClientAuthService.LoginStatusEnum.EXPIRED => LoginStatus.LOGIN_STATUS_EXPIRED
      case ClientAuthService.LoginStatusEnum.NOT_APPLICABLE => LoginStatus.LOGIN_STATUS_NOT_APPLICABLE
      case ClientAuthService.LoginStatusEnum.NOT_AUTHENTICATED => LoginStatus.LOGIN_STATUS_NOT_AUTHENTICATED
      case ClientAuthService.LoginStatusEnum.UNSPECIFIED => LoginStatus.LOGIN_STATUS_UNSPECIFIED
    }
  }

  /**
   * Converts a database authType string to proto AuthType enum.
   */
  def stringToAuthType(authType: String): AuthType = authType.toLowerCase match {
    case "none" => AuthType.AUTH_TYPE_NONE
    case "discover" => AuthType.AUTH_TYPE_DISCOVER
    case "static_auth_header" => AuthType.AUTH_TYPE_STATIC_HEADER
    case _ => AuthType.AUTH_TYPE_UNSPECIFIED
  }

  /**
   * Converts a proto AuthType enum to database authType string.
   */
  def authTypeToString(authType: AuthType): String = authType match {
    case AuthType.AUTH_TYPE_NONE => "none"
    case AuthType.AUTH_TYPE_DISCOVER => "discover"
    case AuthType.AUTH_TYPE_STATIC_HEADER => "static_auth_header"
    case AuthType.AUTH_TYPE_UNSPECIFIED | AuthType.Unrecognized(_) => "none"
  }

  /**
   * Converts a database transportType string to proto TransportType enum.
   */
  def stringToTransportType(transportType: String): TransportType = transportType.toLowerCase match {
    case "streaming_http" => TransportType.TRANSPORT_TYPE_STREAMING_HTTP
    case _ => TransportType.TRANSPORT_TYPE_UNSPECIFIED
  }

  /**
   * Converts a proto TransportType enum to database transportType string.
   */
  def transportTypeToString(transportType: TransportType): String = transportType match {
    case TransportType.TRANSPORT_TYPE_STREAMING_HTTP => "streaming_http"
    case TransportType.TRANSPORT_TYPE_UNSPECIFIED | TransportType.Unrecognized(_) => "streaming_http"
  }

  /**
   * Converts a database row to a McpServer proto message.
   */
  def rowToMcpServer(row: McpServerRow): McpServer = {
    val authType = stringToAuthType(row.authType)

    McpServer(
      uuid = row.uuid.toString,
      name = row.name,
      url = row.url,
      authType = authType,
      transportType = stringToTransportType(row.transportType),
      hasStaticToken = row.staticToken.isDefined,
      oauthConfig = authType match {
        case AuthType.AUTH_TYPE_DISCOVER =>
          Some(OAuthConfig(
            clientId = row.clientId.getOrElse(""),
            tokenEndpoint = row.tokenEndpoint.getOrElse(""),
            hasClientSecret = row.clientSecret.isDefined,
            hasRefreshToken = row.refreshToken.isDefined
          ))
        case _ => None
      },
      createdAt = Some(com.google.protobuf.timestamp.Timestamp(
        seconds = row.createdAt.getTime / 1000,
        nanos = ((row.createdAt.getTime % 1000) * 1000000).toInt
      )),
      updatedAt = Some(com.google.protobuf.timestamp.Timestamp(
        seconds = row.updatedAt.getTime / 1000,
        nanos = ((row.updatedAt.getTime % 1000) * 1000000).toInt
      ))
    )
  }

  /**
   * Converts an EventLogRow to an EventLog proto message.
   */
  def rowToEventLog(row: EventLogRow): dragon.turnstile.api.v1.EventLog = {
    // Convert Circe Json to google.protobuf.Struct
    val metadata: Struct = {
      def circeJsonToProtoValue(json: io.circe.Json): Value = {
        json.fold(
          Value(Value.Kind.NullValue(com.google.protobuf.struct.NullValue.NULL_VALUE)),
          bool => Value(Value.Kind.BoolValue(bool)),
          num => Value(Value.Kind.NumberValue(num.toDouble)),
          str => Value(Value.Kind.StringValue(str)),
          arr => Value(Value.Kind.ListValue(com.google.protobuf.struct.ListValue(arr.map(circeJsonToProtoValue).toSeq))),
          obj => Value(Value.Kind.StructValue(Struct(
            obj.toMap.view.mapValues(circeJsonToProtoValue).toMap
          )))
        )
      }

      row.metadata.asObject match {
        case Some(jsonObject) =>
          Struct(jsonObject.toMap.view.mapValues(circeJsonToProtoValue).toMap)
        case None =>
          Struct()
      }
    }

    dragon.turnstile.api.v1.EventLog(
      id = row.id,
      uuid = row.uuid.toString,
      tenant = row.tenant,
      userId = row.userId.getOrElse(""),
      eventType = row.eventType,
      description = row.description.getOrElse(""),
      metadata = Some(metadata),
      createdAt = Some(com.google.protobuf.timestamp.Timestamp(
        seconds = row.createdAt.getTime / 1000,
        nanos = ((row.createdAt.getTime % 1000) * 1000000).toInt
      ))
    )
  }

}
