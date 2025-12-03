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

import io.grpc.Status
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.http.scaladsl.model.HttpHeader
import pdi.jwt.JwtClaim

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ServerAuthService {
  val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("ServerAuthService")

  case class AuthContext(
    userId: String,
    tenant: String,
    claims: JwtClaim
  )

  // ADT: errors as singletons; successes are represented by Right(AuthContext)
  sealed trait AuthError
  case object MissingAuthHeader extends AuthError
  case object AccessDenied extends AuthError

  def authenticate(
    httpHeader: Seq[HttpHeader],
    authEnabled: Boolean
  )(
    implicit ec: ExecutionContext
  ): Either[AuthError, AuthContext] = {
    if (!authEnabled) {
      // When auth is disabled, return a bypass context
      Right(AuthContext(userId = "bypass", tenant = "default", claims = JwtClaim()))
    } else {
      httpHeader.find(_.name.toLowerCase == "authorization").map(_.value) match {
        case Some(authHeader) => validateToken(Some(authHeader))
        case None => Left(MissingAuthHeader)
      }
    }
  }

  def authenticate(
    metadata: Metadata,
    authEnabled: Boolean
  )(
    implicit ec: ExecutionContext
  ): Future[AuthContext] = {
    if (!authEnabled) {
      // When auth is disabled, return a bypass context
      Future.successful(AuthContext(userId = "bypass", tenant = "default", claims = JwtClaim()))
    } else {
      validateAuth(metadata) match {
        case Right(authContext) => Future.successful(authContext)
        case Left(MissingAuthHeader) =>
          Future
            .failed(Status.UNAUTHENTICATED
              .withDescription("Missing authorization header")
              .asRuntimeException()
            )
        case Left(AccessDenied) =>
          logger.warn("Access denied due to invalid or expired token")
          Future.failed(
            Status.PERMISSION_DENIED
              .withDescription("Access denied: invalid or expired token")
              .asRuntimeException()
          )
      }
    }
  }

  private def validateAuth(
    metadata: Metadata
  )(
    implicit ec: ExecutionContext
  ): Either[AuthError, AuthContext] = {
    validateToken(metadata.getText("authorization"))
  }

  /**
   * Validates the JWT token from gRPC metadata and returns Either[AuthError, AuthContext] wrapped in a Future.
   * Returns Right(AuthContext) on success, Left(MissingAuthHeader) when header missing, or Left(AccessDenied) for invalid/denied tokens.
   */
  private def validateToken(
    tokenOption: Option[String]
  )(
    implicit ec: ExecutionContext
  ): Either[AuthError, AuthContext] = {
    tokenOption match {
      case Some(authHeader) =>
        val tokenWithoutPrefix = authHeader.replace("Bearer ", "")
        ServerOAuthHelper.validateJwt(tokenWithoutPrefix) match {
          case Success(claims) =>
            ServerOAuthHelper.getUserIdFromClaims(claims) match {
              case Some(userId) =>
                // Strip @clients suffix if present. Its an Auth0 convention for machine-to-machine tokens.
                val cleanUserId = userId.replaceAll("@clients$", "")
                Right(AuthContext(cleanUserId, "default", claims))
              case None =>
                logger.debug("Access denied: user ID missing in token claims")
                Left(AccessDenied)
            }
          case Failure(_) =>
            logger.debug("Access denied: token validation failed")
            Left(AccessDenied)
        }
      case None => Left(MissingAuthHeader)
    }
  }

}
