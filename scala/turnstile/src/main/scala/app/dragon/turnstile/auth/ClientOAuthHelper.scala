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

package app.dragon.turnstile.auth

import io.circe*
import io.circe.generic.auto.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import org.slf4j.LoggerFactory

import java.net.URI
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object ClientOAuthHelper {
  private val logger = LoggerFactory.getLogger(getClass)

  // Provide a global execution context for async operations inside this small CLI tool
  implicit private val ec: ExecutionContext = ExecutionContext.global

  private case class Config(
    clientId: String,
    clientSecret: Option[String],
    authorizationEndpoint: String,
    tokenEndpoint: Option[String],
    scope: String,
    redirectHost: String = "localhost",
    redirectPort: Int = 8080,
    redirectPath: String = "/callback",
    timeoutSeconds: Long = 120
  )

  // Use single snake_case models that match upstream JSON directly
  case class TokenResponse(
    access_token: String,
    id_token: Option[String],
    scope: Option[String],
    expires_in: Option[Int],
    token_type: String,
    refresh_token: Option[String],
  )

  case class OpenIdConfigurationResponse(
    authorization_endpoint: Option[String],
    token_endpoint: Option[String],
    registration_endpoint: Option[String]
  )

  case class DcrResponse(
    client_name: Option[String],
    client_id: Option[String],
    client_secret: Option[String],
    redirect_uris: Option[List[String]],
    token_endpoint_auth_method: Option[String],
    client_secret_expires_at: Option[Int]
  )


  // Perform dynamic client registration (DCR) by POSTing JSON metadata to the registration endpoint.
  // Returns a DcrResponse on success.
  def performDCR(
    registrationEndpoint: String,
    redirectUri: String,
  )(
    implicit system: ActorSystem[Nothing]
  ): Future[Either[String, DcrResponse]] = {
    Http().singleRequest(
        HttpRequest(
          method = HttpMethods.POST,
          uri = registrationEndpoint,
          entity = HttpEntity(ContentTypes.`application/json`,
            Json.obj(
              ("client_name", Json.fromString("turnstile-client")),
              ("redirect_uris", Json.arr(Json.fromString(redirectUri))),
              ("response_types", Json.arr(Json.fromString("code"))),
              ("grant_types", Json.arr(Json.fromString("authorization_code"))),
              ("token_endpoint_auth_method", Json.fromString("none"))
            ).noSpaces
          )
        )
      )
      .flatMap { res =>
        res.entity.toStrict(10.seconds).map { entity =>
          (res.status.intValue(), entity.data.utf8String)
        }
      }
      .map {
        case (status, body) if (status >= 200 && status < 300) =>
            io.circe.parser.decode[DcrResponse](body) match {
              case Right(dcrResponse) => Right(dcrResponse)
              case Left(err) => Left(s"Failed to parse DCR response: ${err.getMessage}")
            }
        case (status, body) =>
          logger.error(s"DCR failed with HTTP $status: $body")
          Left(s"HTTP $status")
      }
      .recover { case t => Left(t.getMessage) }
  }

  // Fetch a well-known OpenID Connect configuration and return the parsed Discovery case class.
  // Implemented using Apache Pekko HTTP instead of STTP. This creates a short-lived ActorSystem
  // to perform the request, parses the JSON with circe, and then terminates the ActorSystem.
  def fetchWellKnown(
    domain: String
  )(
    implicit system: ActorSystem[Nothing],
  ): Future[Either[String, OpenIdConfigurationResponse]] = {
    val url = getOAuthAuthorizationServerUrl(domain)

    Http().singleRequest(HttpRequest(uri = url))
      .flatMap { res =>
        res.entity.toStrict(10.seconds).map { entity =>
          val body = entity.data.utf8String
          if (res.status.isSuccess())
            io.circe.parser.decode[OpenIdConfigurationResponse](body).left.map(e => s"Failed to parse discovery: ${e.getMessage}")
          else
            Left(s"HTTP ${res.status.intValue()}")
        }
      }
      .recover { case t => Left(t.getMessage) }
  }


  private def getOAuthAuthorizationServerUrl(
    domain: String
  ): String = {
    // Ensure we have a scheme
    val normalized =
      if (domain.startsWith("http://") || domain.startsWith("https://")) domain
      else s"https://$domain"

    // Parse and reconstruct base (scheme + authority only)
    val uri = new URI(normalized)
    val port = if (uri.getPort == -1) "" else s":${uri.getPort}"

    val base = s"${uri.getScheme}://${uri.getHost}${port}"

    s"$base/.well-known/oauth-authorization-server"
  }


  def buildAuthorizationUrl(
    authEndpoint: String,
    audience: String,
    clientId: String,
    redirectUri: String,
    scope: String,
    state: String
  ): String = {
    val params = Map(
      "audience" -> audience, // No refresh token without audience in Auth0
      "response_type" -> "code",
      "client_id" -> clientId,
      "redirect_uri" -> redirectUri,
      "scope" -> scope,
      "state" -> state
    ).map { case (k, v) => s"${urlEncode(k)}=${urlEncode(v)}" }.mkString("&")
    if (authEndpoint.contains("?")) s"$authEndpoint&$params" else s"$authEndpoint?$params"
  }

  private def urlEncode(
    s: String
  ): String = java.net.URLEncoder.encode(s, "UTF-8")

  def exchangeAuthorizationCode(
    tokenUrl: String,
    clientId: String,
    clientSecretOpt: Option[String],
    code: String,
    redirectUri: String
  )(
    implicit system: ActorSystem[Nothing]
  ): Future[Either[String, TokenResponse]] = {
    // Build form fields
    val baseForm = Map(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "redirect_uri" -> redirectUri,
      "client_id" -> clientId
    )

    val formWithClient = clientSecretOpt match {
      case Some(clientSecret) => baseForm + ("client_secret" -> clientSecret)
      case None => baseForm
    }

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = tokenUrl,
      entity = FormData(formWithClient).toEntity
    )

    Http().singleRequest(request)
      .flatMap { res =>
        res.entity.toStrict(10.seconds).map { strictEntity =>
          val body = strictEntity.data.utf8String
          if (res.status.isSuccess()) {
            io.circe.parser.decode[TokenResponse](body) match {
              case Right(tr) => Right(tr)
              case Left(err) => Left(s"Failed to parse token response: ${err.getMessage}")
            }
          } else {
            Left(s"HTTP ${res.status.intValue()}: $body")
          }
        }
      }
      .recover { case t => Left(t.getMessage) }
  }

  /**
   * Performs an OAuth 2.0 refresh token flow.
   *
   * @param tokenUrl The token endpoint URL
   * @param clientId The OAuth client ID
   * @param clientSecretOpt Optional client secret for authentication
   * @param refreshToken The refresh token to use for obtaining a new access token
   * @param system The actor system for HTTP requests
   * @return Future containing either an error message or a new TokenResponse
   */
  def refreshToken(
    tokenUrl: String,
    clientId: String,
    clientSecretOpt: Option[String],
    refreshToken: String
  )(
    implicit system: ActorSystem[Nothing]
  ): Future[Either[String, TokenResponse]] = {
    // Build form fields
    val baseForm = Map(
      "client_id" -> clientId,
      "grant_type" -> "refresh_token",
      "refresh_token" -> refreshToken
    )

    val formWithClient = clientSecretOpt match {
      case Some(clientSecret) => baseForm + ("client_secret" -> clientSecret)
      case None => baseForm
    }

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = tokenUrl,
      entity = FormData(formWithClient).toEntity
    )

    // The Content-Type header is implicitly set by FormData to application/x-www-form-urlencoded.

    Http().singleRequest(request)
      .flatMap { res =>
        res.entity.toStrict(10.seconds).map { strictEntity =>
          val body = strictEntity.data.utf8String
          if (res.status.isSuccess()) {
            io.circe.parser.decode[TokenResponse](body) match {
              case Right(tr) => Right(tr)
              case Left(err) => Left(s"Failed to parse token response: ${err.getMessage}")
            }
          } else {
            Left(s"HTTP ${res.status.intValue()}: $body")
          }
        }
      }
      .recover { case t => Left(t.getMessage) }
  }

}
