package app.dragon.turnstile.auth

import io.grpc.Status
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.http.scaladsl.model.HttpHeader
import pdi.jwt.JwtClaim

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ServerAuthService {

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
    httpHeader: Seq[HttpHeader]
  )(
    implicit ec: ExecutionContext
  ): Either[AuthError, AuthContext] = {
    httpHeader.find(_.name.toLowerCase == "authorization").map(_.value) match {
      case Some(authHeader) => validateToken(Some(authHeader))
      case None => Left(MissingAuthHeader)
    }
  }

  def authenticate(
    metadata: Metadata
  )(
    implicit ec: ExecutionContext
  ): Future[AuthContext] = {
    validateAuth(metadata) match {
      case Right(authContext) => Future.successful(authContext)
      case Left(MissingAuthHeader) =>
        Future
          .failed(Status.UNAUTHENTICATED
            .withDescription("Missing authorization header")
            .asRuntimeException()
          )
      case Left(AccessDenied) =>
        Future.failed(
          Status.PERMISSION_DENIED
            .withDescription("Access denied: invalid or expired token")
            .asRuntimeException()
        )
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
              case Some(userId) => Right(AuthContext(userId, "default", claims))
              case None => Left(AccessDenied)
            }
          case Failure(_) =>
            Left(AccessDenied)
        }
      case None => Left(MissingAuthHeader)
    }
  }

}
