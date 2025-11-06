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

package app.dragon.turnstile.serializer

import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

/**
 * Serializable wrapper for HttpRequest.
 * Extracts only the necessary data that can be serialized and transmitted across the cluster.
 *
 * Note: This type is designed for cluster serialization. It does NOT preserve:
 * - Streaming entity state
 * - Connection state
 * - Large request bodies (should be handled separately or streamed)
 *
 * Use this when you need to send HTTP request metadata across cluster nodes.
 */
case class SerializableHttpRequest(
  method: String,
  uri: String,
  headers: Seq[(String, String)],
  body: Option[ByteString],
  protocol: String
) extends TurnstileSerializable

object SerializableHttpRequest {
  /**
   * Create a SerializableHttpRequest from a Pekko HttpRequest.
   * This will materialize the request entity (body) if present.
   *
   * WARNING: This will load the entire request body into memory.
   * Do not use for large file uploads or streaming requests.
   */
  def fromHttpRequest(
    request: HttpRequest
  )(implicit ec: ExecutionContext, mat: org.apache.pekko.stream.Materializer): Future[SerializableHttpRequest] = {
    request.entity match {
      case HttpEntity.Strict(_, data) =>
        Future.successful(
          SerializableHttpRequest(
            method = request.method.value,
            uri = request.uri.toString,
            headers = request.headers.map(h => (h.name, h.value)),
            body = Some(data),
            protocol = request.protocol.value
          )
        )
      case _ =>
        // Materialize non-strict entity
        request.entity.toStrict(scala.concurrent.duration.Duration(5, "seconds")).map { strict =>
          SerializableHttpRequest(
            method = request.method.value,
            uri = request.uri.toString,
            headers = request.headers.map(h => (h.name, h.value)),
            body = Some(strict.data),
            protocol = request.protocol.value
          )
        }
    }
  }

  /**
   * Convert SerializableHttpRequest back to Pekko HttpRequest.
   * This is useful when you receive a serialized request and need to process it.
   */
  def toHttpRequest(serializableRequest: SerializableHttpRequest): HttpRequest = {
    val method = HttpMethod.custom(serializableRequest.method)
    val uri = Uri(serializableRequest.uri)
    val headers = serializableRequest.headers.map { case (name, value) =>
      HttpHeader.parse(name, value) match {
        case HttpHeader.ParsingResult.Ok(header, _) => header
        case _ => throw new IllegalArgumentException(s"Invalid header: $name: $value")
      }
    }
    val entity = serializableRequest.body match {
      case Some(data) => HttpEntity(data)
      case None => HttpEntity.Empty
    }

    HttpRequest(
      method = method,
      uri = uri,
      headers = headers.toVector,
      entity = entity
    )
  }
}

/**
 * Serializable wrapper for HttpResponse.
 * Extracts only the necessary data that can be serialized and transmitted across the cluster.
 *
 * Note: This type is designed for cluster serialization. It does NOT preserve:
 * - Streaming entity state
 * - Connection state
 * - Large response bodies (should be handled separately or streamed)
 *
 * Use this when you need to send HTTP response metadata across cluster nodes.
 */
case class SerializableHttpResponse(
  status: Int,
  headers: Seq[(String, String)],
  body: Option[ByteString],
  protocol: String
) extends TurnstileSerializable

object SerializableHttpResponse {
  /**
   * Create a SerializableHttpResponse from a Pekko HttpResponse.
   * This will materialize the response entity (body) if present.
   *
   * WARNING: This will load the entire response body into memory.
   * Do not use for large file downloads or streaming responses.
   */
  def fromHttpResponse(
    response: HttpResponse
  )(implicit ec: ExecutionContext, mat: org.apache.pekko.stream.Materializer): Future[SerializableHttpResponse] = {
    response.entity match {
      case HttpEntity.Strict(_, data) =>
        Future.successful(
          SerializableHttpResponse(
            status = response.status.intValue,
            headers = response.headers.map(h => (h.name, h.value)),
            body = Some(data),
            protocol = response.protocol.value
          )
        )
      case _ =>
        // Materialize non-strict entity
        response.entity.toStrict(scala.concurrent.duration.Duration(5, "seconds")).map { strict =>
          SerializableHttpResponse(
            status = response.status.intValue,
            headers = response.headers.map(h => (h.name, h.value)),
            body = Some(strict.data),
            protocol = response.protocol.value
          )
        }
    }
  }

  /**
   * Convert SerializableHttpResponse back to Pekko HttpResponse.
   * This is useful when you receive a serialized response and need to send it to a client.
   */
  def toHttpResponse(serializableResponse: SerializableHttpResponse): HttpResponse = {
    val status = StatusCode.int2StatusCode(serializableResponse.status)
    val headers = serializableResponse.headers.map { case (name, value) =>
      HttpHeader.parse(name, value) match {
        case HttpHeader.ParsingResult.Ok(header, _) => header
        case _ => throw new IllegalArgumentException(s"Invalid header: $name: $value")
      }
    }
    val entity = serializableResponse.body match {
      case Some(data) => HttpEntity(data)
      case None => HttpEntity.Empty
    }

    HttpResponse(
      status = status,
      headers = headers.toVector,
      entity = entity
    )
  }
}
