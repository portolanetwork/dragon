package app.dragon.turnstile.auth

import app.dragon.turnstile.actor.{ActorLookup, AuthCodeFlowActor}
import app.dragon.turnstile.db.DbInterface
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

object ClientAuthService {
  case class AuthToken(
    accessToken: String,
    expiresIn: Option[Int],
    refreshToken: String,
  )

  private case class CachedToken(
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
  )

  // Thread-safe cache for access tokens
  private val tokenCache: ConcurrentHashMap[String, CachedToken] =
    new ConcurrentHashMap[String, CachedToken]()

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
              mcpServerUuid = mcpServerUuid,
              domain = serverRow.url,
              clientId = serverRow.clientId,
              clientSecret = serverRow.clientSecret,
              replyTo = replyTo
            )
          ).flatMap {
            case AuthCodeFlowActor.FlowAuthResponse(returnedMcpServerUuid, loginUrl, tokenEndpoint, clientId, clientSecret) =>
              // Update the database with OAuth configuration
              DbInterface.updateMcpServerAuth(
                uuid = serverUuid,
                clientId = Some(clientId),
                clientSecret = clientSecret,
                tokenEndpoint = Some(tokenEndpoint)
              ).map {
                case Right(_) => Right(loginUrl)
                case Left(dbError) => Left(DatabaseError(s"Failed to update OAuth config: ${dbError.message}"))
              }
            case AuthCodeFlowActor.FlowFailed(error) =>
              Future.successful(Left(AuthFlowFailed(s"Auth flow failed: $error")))
            case AuthCodeFlowActor.FlowTokenResponse(_, _, _) =>
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
      case AuthCodeFlowActor.FlowTokenResponse(mcpServerUuid, accessToken, refreshToken) =>
        // Store the refresh token in the database
        DbInterface.updateMcpServerAuth(
          uuid = UUID.fromString(mcpServerUuid),
          refreshToken = Some(refreshToken),
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
      case AuthCodeFlowActor.FlowAuthResponse(_, _, _, _, _) =>
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
    val now = Instant.now()

    // Check cache first
    Option(tokenCache.get(mcpServerUuid)) match {
      case Some(cached) if cached.expiresAt.isAfter(now) =>
        // Cache hit with valid token
        Future.successful(Right(AuthToken(
          accessToken = cached.accessToken,
          expiresIn = Some(cached.expiresAt.getEpochSecond.toInt - now.getEpochSecond.toInt),
          refreshToken = cached.refreshToken
        )))

      case _ =>
        // Cache miss or expired token - fetch new token
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
                      // Calculate expiry time with 60 second buffer for safety
                      val expiresInSeconds = tokenResponse.expires_in.getOrElse(3600)
                      val expiresAt = now.plusSeconds(expiresInSeconds - 60)

                      // Update cache
                      tokenCache.put(mcpServerUuid, CachedToken(
                        accessToken = tokenResponse.access_token,
                        refreshToken = tokenResponse.refresh_token,
                        expiresAt = expiresAt
                      ))

                      // Write refresh token back to database
                      DbInterface.updateMcpServerAuth(
                        uuid = UUID.fromString(mcpServerUuid),
                        refreshToken = Some(tokenResponse.refresh_token)
                      ).map {
                        case Right(_) =>
                          Right(AuthToken(
                            accessToken = tokenResponse.access_token,
                            expiresIn = tokenResponse.expires_in,
                            refreshToken = tokenResponse.refresh_token
                          ))
                        case Left(dbError) =>
                          // Still return the token even if DB update fails, but log the error
                          // This ensures the caller can proceed even if DB write fails
                          Left(DatabaseError(s"Failed to update refresh token in database: ${dbError.message}"))
                      }
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

}
