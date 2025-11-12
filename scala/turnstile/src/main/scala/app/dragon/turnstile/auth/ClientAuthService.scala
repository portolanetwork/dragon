package app.dragon.turnstile.auth

import app.dragon.turnstile.actor.{ActorLookup, AuthCodeFlowActor}
import app.dragon.turnstile.auth.ServerAuthService.AuthError
import app.dragon.turnstile.db.{DbError, DbInterface, McpServerRow}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

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
  case class AuthFlowFailed(message: String) extends AuthError

  def initiateAuthCodeFlow(
    mcpServerUuid: String
  )(
    implicit db: Database,
    ec: ExecutionContext,
    sharding: ClusterSharding,
    system: ActorSystem[?]
  ): Future[Either[AuthError, String]] = {
    implicit val timeout: Timeout = Timeout(30.seconds)

    for {
      mcpServerRowEither <- DbInterface.findMcpServerByUuid(UUID.fromString(mcpServerUuid))
      loginUrl <- mcpServerRowEither match {
        case Right(serverRow) =>
          val flowId = UUID.randomUUID().toString

          ActorLookup.getAuthCodeFlowActor(flowId).ask[AuthCodeFlowActor.FlowResponse](replyTo =>
            AuthCodeFlowActor.StartFlow(
              domain = serverRow.url,
              clientId = serverRow.clientId,
              clientSecret = serverRow.clientSecret,
              replyTo = replyTo
            )
          ).map {
            case AuthCodeFlowActor.FlowAuthResponse(loginUrl, tokenEndpoint, clientId, clientSecret) =>
              Right(loginUrl)
            case AuthCodeFlowActor.FlowFailed(error) =>
              Left(AuthFlowFailed(s"Auth flow failed: $error"))
            case AuthCodeFlowActor.FlowComplete(_) =>
              Left(AuthFlowFailed("Unexpected FlowComplete response during flow initiation"))
          }

        case Left(dbError) =>
          Future.successful(Left(DatabaseError(dbError.toString)))
      }
    } yield loginUrl
  }

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
