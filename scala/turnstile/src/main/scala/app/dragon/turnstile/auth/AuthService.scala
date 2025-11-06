package app.dragon.turnstile.auth

import io.grpc.Status
import org.apache.pekko.grpc.scaladsl.Metadata
import pdi.jwt.JwtClaim

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

object AuthService {

  case class AuthContext(
    userId: String,
    tenant: String,
    claims: JwtClaim
  )

  // ADT: errors as singletons; successes are represented by Right(AuthContext)
  private sealed trait AuthError

  private case object MissingAuthHeader extends AuthError

  private case object AccessDenied extends AuthError


  def authenticate(
    metadata: Metadata
  )(
    implicit ec: ExecutionContext
  ): Future[AuthContext] = {
    validateToken(metadata) match {
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

  /**
   * Validates the JWT token from gRPC metadata and returns Either[AuthError, AuthContext] wrapped in a Future.
   * Returns Right(AuthContext) on success, Left(MissingAuthHeader) when header missing, or Left(AccessDenied) for invalid/denied tokens.
   */
  private def validateToken(
    metadata: Metadata
  )(
    implicit ec: ExecutionContext
  ): Either[AuthError, AuthContext] = {
    metadata.getText("authorization") match {
      case Some(authHeader) =>
        val tokenWithoutPrefix = authHeader.replace("Bearer ", "")
        OAuthHelper.validateJwt(tokenWithoutPrefix) match {
          case Success(claims) =>
            OAuthHelper.getUserIdFromClaims(claims) match {
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
