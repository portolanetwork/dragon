package app.dragon.turnstile.auth

import app.dragon.turnstile.auth.ServerAuthService.AuthError
import app.dragon.turnstile.db.{DbError, DbInterface, McpServerRow}
import org.apache.pekko.actor.typed.ActorSystem
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

object ClientAuthService {
  case class AuthToken(
    accessToken: String,
    expiresIn: Option[Int],
    refreshToken: Option[String],
  )

  sealed trait AuthError {
    def message: String
  }
  case class TokenFetchFailed(message: String) extends AuthError
  case class MissingConfiguration(message: String) extends AuthError
  case class DatabaseError(message: String) extends AuthError
  case class ServerNotFound(message: String) extends AuthError

  def getAuthToken(
    mcpServerUuid: String
  )(
    implicit db: Database,
    ec: ExecutionContext,
    system: ActorSystem[?]
  ): Future[Either[AuthError, AuthToken]] = {
    for {
      mcpServerRowEither <- DbInterface.findMcpServerByUuid(UUID.fromString(mcpServerUuid))
      authToken <- mcpServerRowEither match {
        case Right(serverRow) =>
          serverRow.refreshToken match {
            case Some(refreshToken) =>
              val tokenEndpoint = serverRow.tokenEndpoint.getOrElse("")
              val clientId = serverRow.clientId.getOrElse("")
              val clientSecret = serverRow.clientSecret

              ClientOAuthHelper.refreshToken(
                tokenUrl = tokenEndpoint,
                clientId = clientId,
                clientSecretOpt = clientSecret,
                refreshToken = refreshToken
              ).flatMap {
                case Left(errorMessage) =>
                  Future.successful(Left(TokenFetchFailed(s"Failed to refresh token: $errorMessage")))
                case Right(tokenResponse) =>
                  Future.successful(Right(AuthToken(
                    accessToken = tokenResponse.access_token,
                    expiresIn = tokenResponse.expires_in,
                    refreshToken = tokenResponse.refresh_token
                  )))
              }
            case None =>
              Future.successful(Left(MissingConfiguration("No refresh token available for MCP server")))
          }
        case Left(dbError) =>
          Future.successful(Left(DatabaseError(dbError.toString)))
      }
    } yield authToken
  }
  
}
