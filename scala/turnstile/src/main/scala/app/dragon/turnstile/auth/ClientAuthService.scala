package app.dragon.turnstile.auth

import app.dragon.turnstile.db.DbInterface
import app.dragon.turnstile.utils.ActorLookup
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import slick.jdbc.JdbcBackend.Database

import java.time.Instant
import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object ClientAuthService {
  val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  case class AuthToken(
    accessToken: String,
    expiresIn: Option[Int],
    refreshToken: Option[String],
  )

  // New enum representing login status categories
  enum LoginStatusEnum {
    case AUTHENTICATED, EXPIRED, NOT_APPLICABLE, NOT_AUTHENTICATED, UNSPECIFIED
  }

  case class LoginStatusInfo(
    uuid: String,
    authType: String,
    loginStatusEnum: LoginStatusEnum,
  )


  private case class CachedToken(
    accessToken: String,
    refreshToken: Option[String],
    expiresAt: Instant
  )

  // Thread-safe cache for access tokens
  private val tokenCache: TrieMap[String, CachedToken] =
    TrieMap.empty[String, CachedToken]

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
          refreshToken = refreshToken,
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

  /**
   * Retrieves an authentication token for an MCP server, using a shared cache to minimize token refreshes.
   * The cache is node-local and shared across all requests on this node.
   *
   * @param mcpServerUuid The UUID of the MCP server
   * @return Either[AuthError, AuthToken] containing the cached or newly fetched token
   */
  def getAuthTokenCached(
    mcpServerUuid: String
  )(
    implicit db: Database,
    ec: ExecutionContext,
    system: ActorSystem[?]
  ): Future[Either[AuthError, AuthToken]] = {
    val now = Instant.now()

    // Check cache first
    tokenCache.get(mcpServerUuid) match {
      case Some(cached) if cached.expiresAt.isAfter(now) =>
        logger.debug(s"+++++ ClientAuthService: Cache hit for MCP server $mcpServerUuid")

        // Cache hit with valid token
        Future.successful(Right(AuthToken(
          accessToken = cached.accessToken,
          expiresIn = Some(cached.expiresAt.getEpochSecond.toInt - now.getEpochSecond.toInt),
          refreshToken = cached.refreshToken
        )))

      case _ =>
        logger.debug(s"------------- ClientAuthService: Cache miss for MCP server $mcpServerUuid")

        // Cache miss or expired token - fetch new token
        getAuthToken(mcpServerUuid).map {
          case Right(tokenResponse) =>
            // Calculate expiry time with 60 second buffer for safety
            val expiresInSeconds = tokenResponse.expires_in.getOrElse(3600)
            val expiresAt = now.plusSeconds(expiresInSeconds - 60)

            // Update cache
            tokenCache.put(mcpServerUuid, CachedToken(
              accessToken = tokenResponse.access_token,
              refreshToken = tokenResponse.refresh_token,
              expiresAt = expiresAt
            ))

            Right(AuthToken(
              accessToken = tokenResponse.access_token,
              expiresIn = tokenResponse.expires_in,
              refreshToken = tokenResponse.refresh_token
            ))
          case Left(authError) =>
            Left(authError)
        }


    }
  }

  private def getAuthToken(
    mcpServerUuid: String
  )(
    implicit db: Database,
    ec: ExecutionContext,
    system: ActorSystem[?]
  ): Future[Either[AuthError, ClientOAuthHelper.TokenResponse]] = {
    val now = Instant.now()

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
                case Right(tokenResponse) if tokenResponse.refresh_token.isDefined =>
                  logger.info(s"Refreshed access + refresh token for MCP server $mcpServerUuid")

                  // Write refresh token back to database
                  DbInterface.updateMcpServerAuth(
                    uuid = UUID.fromString(mcpServerUuid),
                    refreshToken = tokenResponse.refresh_token
                  ).map {
                    case Right(_) =>
                      Right(tokenResponse)
                    case Left(dbError) =>
                      Left(DatabaseError(s"Failed to update refresh token in database: ${dbError.message}"))
                  }
                case Right(tokenResponse) =>
                  logger.info(s"Refreshed access token for MCP server $mcpServerUuid")

                  Future.successful(Right(tokenResponse))
              }
            case None =>
              Future.successful(Left(MissingConfiguration("No refresh token available for MCP server")))
          }
        case Left(dbError) =>
          Future.successful(Left(DatabaseError(dbError.toString)))
      }
    } yield authToken
  }
  
  /**
   * Get the login status for an MCP server.
   *
   * @param mcpServerUuid The UUID of the MCP server
   * @return Either[AuthError, LoginStatusInfo] containing the login status information
   */
  def getMcpServerLoginStatus(
    mcpServerUuid: String
  )(
    implicit db: Database,
    ec: ExecutionContext,
    system: ActorSystem[?]
  ): Future[Either[AuthError, LoginStatusInfo]] = {
    val now = Instant.now()
    val serverUuid = UUID.fromString(mcpServerUuid)

    for {
      mcpServerRowEither <- DbInterface.findMcpServerByUuid(serverUuid)
      statusInfo <- mcpServerRowEither match {
        case Right(serverRow) =>
          val authType = serverRow.authType.toLowerCase

          // For now treat presence of a refresh token as authenticated (this can be refined later)
          val isAuth = serverRow.refreshToken.isDefined

          val statusEnum = authType match {
            case "none" | "static_auth_header" => LoginStatusEnum.NOT_APPLICABLE
            case "discover" if isAuth => LoginStatusEnum.AUTHENTICATED
            case "discover" => LoginStatusEnum.NOT_AUTHENTICATED
            case _ => LoginStatusEnum.UNSPECIFIED
          }

          Future.successful(Right(LoginStatusInfo(
            uuid = serverRow.uuid.toString,
            authType = serverRow.authType,
            loginStatusEnum = statusEnum
          )))
        case Left(dbError) =>
          Future.successful(Left(DatabaseError(dbError.toString)))
      }
    } yield statusInfo
  }

  /**
   * Logout from an MCP server by clearing cached tokens and removing refresh token from database.
   *
   * @param mcpServerUuid The UUID of the MCP server to logout from
   * @return Either[AuthError, Unit] indicating success or failure
   */
  def logoutFromMcpServer(
    mcpServerUuid: String
  )(
    implicit db: Database,
    ec: ExecutionContext
  ): Future[Either[AuthError, Unit]] = {
    val serverUuid = UUID.fromString(mcpServerUuid)

    // Remove from cache
    tokenCache.remove(mcpServerUuid)
    logger.info(s"Cleared cached token for MCP server $mcpServerUuid")

    // Remove refresh token from database
    DbInterface.updateMcpServerAuth(
      uuid = serverUuid,
      refreshToken = None
    ).map {
      case Right(_) =>
        logger.info(s"Successfully logged out from MCP server $mcpServerUuid")
        Right(())
      case Left(dbError) =>
        logger.error(s"Failed to clear refresh token from database: ${dbError.message}")
        Left(DatabaseError(s"Failed to clear refresh token: ${dbError.message}"))
    }
  }

}
