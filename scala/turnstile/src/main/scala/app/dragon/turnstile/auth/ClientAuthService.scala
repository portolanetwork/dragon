package app.dragon.turnstile.auth

import app.dragon.turnstile.actor.{ActorLookup, AuthCodeFlowActor}
import app.dragon.turnstile.db.DbInterface
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object ClientAuthService {
  case class AuthToken(
    accessToken: String,
    expiresIn: Option[Int],
    refreshToken: String,
  )

  sealed trait AuthError {
    def message: String
  }
  case class TokenFetchFailed(message: String) extends AuthError
  case class MissingConfiguration(message: String) extends AuthError
  case class DatabaseError(message: String) extends AuthError
  case class ServerNotFound(message: String) extends AuthError
  private case class AuthFlowFailed(message: String) extends AuthError

  def initiateAuthCodeFlow(
    mcpServerUuid: String
  )(
    implicit db: Database,
    ec: ExecutionContext,
    sharding: ClusterSharding,
    system: ActorSystem[?]
  ): Future[Either[AuthError, String]] = {
    implicit val timeout: Timeout = Timeout(30.seconds)

    val serverUuid = UUID.fromString(mcpServerUuid)

    for {
      mcpServerRowEither <- DbInterface.findMcpServerByUuid(serverUuid)
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
          ).flatMap {
            case AuthCodeFlowActor.FlowAuthResponse(loginUrl, tokenEndpoint, clientId, clientSecret) =>
              // Update the database with OAuth configuration
              DbInterface.updateMcpServerAuth(
                uuid = serverUuid,
                clientId = Some(clientId),
                clientSecret = clientSecret,
                refreshToken = None, // Don't update refreshToken
                tokenEndpoint = Some(tokenEndpoint)
              ).map {
                case Right(_) => Right(loginUrl)
                case Left(dbError) => Left(DatabaseError(s"Failed to update OAuth config: ${dbError.message}"))
              }
            case AuthCodeFlowActor.FlowFailed(error) =>
              Future.successful(Left(AuthFlowFailed(s"Auth flow failed: $error")))
            case AuthCodeFlowActor.FlowTokenResponse(_, _) =>
              Future.successful(Left(AuthFlowFailed("Unexpected FlowComplete response during flow initiation")))
          }

        case Left(dbError) =>
          Future.successful(Left(DatabaseError(dbError.toString)))
      }
    } yield loginUrl
  }

  def exchangeAuthCode(
    code: String,
    state: String
  )(
    implicit db: Database,
    ec: ExecutionContext,
    sharding: ClusterSharding,
    system: ActorSystem[?]
  ): Future[Either[AuthError, AuthToken]] = {
    implicit val timeout: Timeout = Timeout(30.seconds)

    // State should be the flowId
    val flowId = state

    ActorLookup.getAuthCodeFlowActor(flowId).ask[AuthCodeFlowActor.FlowResponse](replyTo =>
      AuthCodeFlowActor.GetToken(
        state = state,
        code = code,
        replyTo = replyTo
      )
    ).flatMap {
      case AuthCodeFlowActor.FlowTokenResponse(accessToken, refreshToken) =>
        // Store the refresh token in the database
        DbInterface.updateMcpServerAuth(
          uuid = UUID.fromString(flowId),
          clientId = None, // Don't update clientId
          clientSecret = None, // Don't update clientSecret
          refreshToken = Some(refreshToken),
          tokenEndpoint = None // Don't update tokenEndpoint
        ).flatMap {
          case Right(_) =>
            Future.successful(Right(AuthToken(
              accessToken = accessToken,
              expiresIn = None,
              refreshToken = refreshToken
            )))
          case Left(dbError) =>
            Future.successful(Left(DatabaseError(s"Failed to update refresh token: ${dbError.message}")))
        }
      case AuthCodeFlowActor.FlowFailed(error) =>
        Future.successful(Left(AuthFlowFailed(s"Token exchange failed: $error")))
      case AuthCodeFlowActor.FlowAuthResponse(_, _, _, _) =>
        Future.successful(Left(AuthFlowFailed("Unexpected FlowAuthResponse during token exchange")))
    }
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
                case Right(tokenResponse: ClientOAuthHelper.TokenResponse) =>
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
