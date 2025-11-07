package app.dragon.turnstile.auth

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.circe.*
import io.circe.generic.auto.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, HttpEntity, ContentTypes, FormData}
import org.apache.pekko.http.scaladsl.model.HttpCharsets
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import org.slf4j.LoggerFactory
import sttp.client4.*
import sttp.client4.circe.*
import sttp.client4.httpclient.HttpClientSyncBackend

import java.awt.Desktop
import java.io.OutputStream
import java.net.{InetSocketAddress, URI}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ClientAuthService {
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
    refresh_token: Option[String]
  )

  private case class Discovery(
    authorization_endpoint: Option[String],
    token_endpoint: Option[String],
    registration_endpoint: Option[String]
  )

  private case class DcrResponse(
    client_id: Option[String],
    client_secret: Option[String],
    registration_client_uri: Option[String],
    token_endpoint: Option[String]
  )

  /**
   * Connect to an OAuth server and obtain access and refresh tokens.
   *
   * @param mcpUrl The base URL of the OAuth server (e.g., "https://auth.example.com")
   * @param clientId Optional client ID. If not provided, DCR will be attempted.
   * @param clientSecret Optional client secret. Required if clientId is provided.
   * @return Future of Either an error message or TokenResponse with access and refresh tokens
   */
  def connect(
    mcpUrl: String,
    clientId: Option[String],
    clientSecret: Option[String],
    scope: String = "openid profile email",
    redirectPort: Int = 8080
  )(
    implicit system: ActorSystem[Nothing],
  ): Future[Either[String, TokenResponse]] = {
    logger.info(s"Connecting to OAuth server at $mcpUrl")

    // Discover endpoints asynchronously
    fetchWellKnown(mcpUrl).flatMap {
      case Left(err) =>
        Future.successful(Left(s"Failed to fetch well-known configuration from $mcpUrl: $err"))
      case Right(discovered) =>
        Future {
          // Imperative flow that returns a single Either value without using `return`
          // 1) authorization endpoint
          discovered.authorization_endpoint match {
            case None => Left("well-known document did not contain authorization_endpoint")
            case Some(authorizationEndpoint) =>
              // 2) token endpoint must exist (or may be provided later by DCR)
              var tokenEndpointOpt: Option[String] = discovered.token_endpoint
              if (tokenEndpointOpt.isEmpty && clientId.nonEmpty) {
                // If no token endpoint in discovery and clientId provided, we cannot proceed
                Left("well-known document did not contain token_endpoint")
              } else {
                // 3) determine client id/secret (possibly via DCR)
                var finalClientId: String = clientId.getOrElse("")
                var finalClientSecret: Option[String] = clientSecret

                if (clientId.isEmpty) {
                  // DCR requested
                  discovered.registration_endpoint match {
                    case None => Left("DCR requested but no registration_endpoint found in discovery document")
                    case Some(registrationEndpoint) =>
                      val redirectUri = s"http://localhost:${redirectPort}/callback"
                      val dcrEither = Await.result(performDynamicClientRegistration(registrationEndpoint, redirectUri, Map.empty)(system), 20.seconds)
                      dcrEither match {
                        case Left(err) => Left(s"Dynamic client registration failed: $err")
                        case Right(dcrResp) =>
                          finalClientId = dcrResp.client_id.getOrElse("")
                          if (finalClientId.trim.isEmpty) Left("DCR response did not contain client_id")
                          else {
                            finalClientSecret = dcrResp.client_secret.orElse(finalClientSecret)
                            tokenEndpointOpt = dcrResp.token_endpoint.orElse(tokenEndpointOpt)

                            // proceed to auth flow after DCR
                            if (finalClientId.trim.isEmpty) Left("client_id is empty — provide a client id or omit it to use dynamic registration")
                            else {
                              val cfg = Config(
                                clientId = finalClientId,
                                clientSecret = finalClientSecret,
                                authorizationEndpoint = authorizationEndpoint,
                                tokenEndpoint = tokenEndpointOpt,
                                scope = scope,
                                redirectPort = redirectPort
                              )

                              runAuthCodeFlow(cfg) match {
                                case Right(Some(tokenResponse)) =>
                                  logger.info("Successfully obtained access token")
                                  Right(tokenResponse)
                                case Right(None) => Left("Token endpoint not configured; unable to complete token exchange")
                                case Left(err) => Left(s"Authorization flow failed: $err")
                              }
                            }
                          }
                      }
                  }
                } else {
                  // clientId provided path
                  if (finalClientId.trim.isEmpty) Left("client_id is empty — provide a client id or omit it to use dynamic registration")
                  else {
                    val cfg = Config(
                      clientId = finalClientId,
                      clientSecret = finalClientSecret,
                      authorizationEndpoint = authorizationEndpoint,
                      tokenEndpoint = tokenEndpointOpt,
                      scope = scope,
                      redirectPort = redirectPort
                    )

                    runAuthCodeFlow(cfg) match {
                      case Right(Some(tokenResponse)) =>
                        logger.info("Successfully obtained access token")
                        Right(tokenResponse)
                      case Right(None) => Left("Token endpoint not configured; unable to complete token exchange")
                      case Left(err) => Left(s"Authorization flow failed: $err")
                    }
                  }
                }
              }
          }
        }(ec)
    }
  }

  // Perform dynamic client registration (DCR) by POSTing JSON metadata to the registration endpoint.
  // Returns a DcrResponse on success.
  private def performDynamicClientRegistration(
    registrationEndpoint: String,
    redirectUri: String,
    metadata: Map[String, String]
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
              ("grant_types", Json.arr(Json.fromString("authorization_code"))),
              ("token_endpoint_auth_method", Json.fromString("client_secret_basic"))
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
        case (status, body) =>
          if (status >= 200 && status < 300) {
            io.circe.parser.decode[DcrResponse](body) match {
              case Right(d) => Right(d)
              case Left(err) => Left(s"Failed to parse DCR response: ${err.getMessage}")
            }
          } else Left(s"HTTP $status")
      }
      .recover { case t => Left(t.getMessage) }
  }

   // Fetch a well-known OpenID Connect configuration and return the parsed Discovery case class.
   // Implemented using Apache Pekko HTTP instead of STTP. This creates a short-lived ActorSystem
   // to perform the request, parses the JSON with circe, and then terminates the ActorSystem.
  private def fetchWellKnown(
    domain: String
  )(
      implicit system: ActorSystem[Nothing],
    ): Future[Either[String, Discovery]] = {
    val url = createWellKnownUrl_OpenIdCOnfiguration(domain)

    Http().singleRequest(HttpRequest(uri = url))
      .flatMap { res =>
        res.entity.toStrict(10.seconds).map { entity =>
          val body = entity.data.utf8String
          if (res.status.isSuccess())
            io.circe.parser.decode[Discovery](body).left.map(e => s"Failed to parse discovery: ${e.getMessage}")
          else
            Left(s"HTTP ${res.status.intValue()}")
        }
      }
      .recover { case t => Left(t.getMessage) }
  }


  private def createWellKnownUrl_OpenIdCOnfiguration(
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
    
    s"$base/.well-known/openid-configuration"
  }
  
  private def getAuthUrl(
    clientId: String,
    discovery: Discovery,
    redirectUrl: String,
    scope: String
  ): Try[String] = {
    val state = java.util.UUID.randomUUID().toString
    
    discovery.authorization_endpoint match {
      case None => 
        Failure(new Exception("well-known document did not contain authorization_endpoint"))
      case Some(authEndpoint) =>
        Success(buildAuthorizationUrl(authEndpoint, clientId, redirectUrl, scope, state))
    }
  }
  
  
  

  private def runAuthCodeFlow(
     cfg: Config
   )(implicit system: ActorSystem[Nothing]): Either[String, Option[TokenResponse]] = {
     val redirectUri = s"http://${cfg.redirectHost}:${cfg.redirectPort}${cfg.redirectPath}"
     val state = java.util.UUID.randomUUID().toString

     // Basic validations to prevent malformed URLs (user saw Desktop.browse failure with an URL starting with '?')
     if (cfg.authorizationEndpoint == null || cfg.authorizationEndpoint.trim.isEmpty) {
       return Left("authorization endpoint is empty — provide a full URL or use discovery (discover:domain)")
     }
     if (!cfg.authorizationEndpoint.startsWith("http://") && !cfg.authorizationEndpoint.startsWith("https://")) {
       return Left(s"authorization endpoint must be an absolute URL starting with http:// or https:// — got: ${cfg.authorizationEndpoint}")
     }
     if (cfg.clientId == null || cfg.clientId.trim.isEmpty) {
       return Left("client_id is empty — provide a client id or use dynamic registration (set clientId to dcr:auto)")
     }

     val authUrl = buildAuthorizationUrl(cfg.authorizationEndpoint, cfg.clientId, redirectUri, cfg.scope, state)
     // Ensure the URL parses as a valid URI before attempting to open it in a browser
     try {
       new URI(authUrl)
     } catch {
       case t: Throwable => return Left(s"Constructed authorization URL is invalid: $authUrl; error: ${t.getMessage}")
     }
     logger.info(s"Authorization URL: $authUrl")

     // Start callback server
     val latch = new CountDownLatch(1)
     val result = scala.collection.mutable.Map[String, String]()

     val serverTry = Try(startCallbackServer(cfg.redirectPort, cfg.redirectPath, params => {
       params.get("state").foreach(s => result.put("state", s))
       params.get("code").foreach(c => result.put("code", c))
       params.get("error").foreach(e => result.put("error", e))
       latch.countDown()
     }))

     serverTry.fold(
       t => Left(s"Failed to start callback server: ${t.getMessage}"),
       server => {
         // Open browser
         openInBrowser(authUrl)

         val awaited = latch.await(cfg.timeoutSeconds, TimeUnit.SECONDS)
         try {
           server.stop(0)
         } catch {
           case _: Throwable => // ignore
         }

         val flowResult: Either[String, Option[TokenResponse]] =
           if (!awaited) Left("Timed out waiting for authorization code")
           else if (result.contains("error")) Left(s"Authorization server returned error: ${result("error")}")
           else if (!result.contains("code")) Left("No authorization code received")
           else {
             val code = result("code")
             val receivedState = result.get("state")
             if (!receivedState.contains(state)) {
               logger.warn(s"Received state does not match sent state. sent=$state received=${receivedState.getOrElse("<none>")}")
             }

             // If token endpoint configured, exchange
             cfg.tokenEndpoint match {
               case Some(tokenUrl) =>
                 // exchangeAuthorizationCode is now async; block briefly to keep this flow synchronous
                 val exchangeEither = Await.result(exchangeAuthorizationCode(tokenUrl, cfg.clientId, cfg.clientSecret, code, redirectUri), 30.seconds)
                 exchangeEither.left.map(err => s"Token exchange failed: $err").map(Some(_))
               case None => Right(None)
             }
           }

         flowResult
       }
     )
   }

   private def buildAuthorizationUrl(
     authEndpoint: String,
     clientId: String,
     redirectUri: String,
     scope: String,
     state: String
   ): String = {
     val params = Map(
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

   private def openInBrowser(
     url: String
   ): Unit = {
     Try {
       if (Desktop.isDesktopSupported) {
         try {
           Desktop.getDesktop.browse(new URI(url))
         } catch {
           case t: Throwable =>
             // On macOS Desktop.browse occasionally fails with error -50 for malformed/empty URLs.
             // Try a platform-specific fallback before giving up.
             logger.warn(s"Desktop.browse failed: ${t.getMessage}; trying platform fallback...")
             val osName = System.getProperty("os.name", "").toLowerCase
             try {
               if (osName.contains("mac")) {
                 Runtime.getRuntime.exec(Array("open", url))
               } else if (osName.contains("win")) {
                 Runtime.getRuntime.exec(Array("rundll32", "url.dll,FileProtocolHandler", url))
               } else {
                 Runtime.getRuntime.exec(Array("xdg-open", url))
               }
             } catch {
               case t2: Throwable => logger.warn(s"Platform fallback to open browser failed: ${t2.getMessage}")
             }
         }
       } else {
         // Fallback: print the URL for manual copy
         logger.info(s"Please open the following URL in a browser: $url")
       }
     }.recover {
       case t => logger.warn(s"Unable to open browser automatically: ${t.getMessage}")
     }
   }

   private def startCallbackServer(
     port: Int,
     path: String,
     onParams: Map[String, String] => Unit
   ): HttpServer = {
     val server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0)

     server.createContext(path, (exchange: HttpExchange) => {
       try {
         val query = Option(exchange.getRequestURI.getQuery).getOrElse("")
         val params = parseQuery(query)
         // Respond with a simple HTML page
         val response =
           s"<html><body><h1>Authorization received</h1><p>You can close this window.</p></body></html>"
         val bytes = response.getBytes("UTF-8")
         exchange.getResponseHeaders.add("Content-Type", "text/html; charset=UTF-8")
         exchange.sendResponseHeaders(200, bytes.length)
         val os: OutputStream = exchange.getResponseBody
         os.write(bytes)
         os.close()

         // Deliver params to callback
         onParams(params)
       } catch {
         case t: Throwable =>
           logger.error("Error handling callback request", t)
           try {
             val resp = "<html><body><h1>Error processing request</h1></body></html>".getBytes("UTF-8")
             exchange.sendResponseHeaders(500, resp.length)
             val os = exchange.getResponseBody
             os.write(resp)
             os.close()
           } catch {
             case _: Throwable => // ignore
           }
       }
     })

     server.setExecutor(null)
     server.start()
     logger.info(s"Started local callback server on port $port path $path")
     server
   }

   private def parseQuery(
     query: String
   ): Map[String, String] = {
     if (query.isEmpty) Map.empty
     else {
       query.split("&").toList.flatMap { part =>
         part.split("=", 2) match {
           case Array(k, v) => Some(java.net.URLDecoder.decode(k, "UTF-8") -> java.net.URLDecoder.decode(v, "UTF-8"))
           case Array(k) => Some(java.net.URLDecoder.decode(k, "UTF-8") -> "")
           case _ => None
         }
       }.toMap
     }
   }

   private def exchangeAuthorizationCode(
     tokenUrl: String,
     clientId: String,
     clientSecretOpt: Option[String],
     code: String,
     redirectUri: String
   )(implicit system: ActorSystem[Nothing]): Future[Either[String, TokenResponse]] = {
     // Build form fields
     val baseForm = Map(
       "grant_type" -> "authorization_code",
       "code" -> code,
       "redirect_uri" -> redirectUri
     )

     val formWithClient = clientSecretOpt match {
       case Some(_) => baseForm
       case None => baseForm + ("client_id" -> clientId)
     }

     // Create request entity using Pekko FormData (provide charset)
     val entity = FormData(formWithClient).toEntity(HttpCharsets.`UTF-8`)

     // Build headers (use basic auth when secret provided)
     val headers = clientSecretOpt match {
       case Some(secret) => List(Authorization(BasicHttpCredentials(clientId, secret)))
       case None => Nil
     }

     val request = HttpRequest(
       method = HttpMethods.POST,
       uri = tokenUrl,
       entity = entity
     ).withHeaders(headers)

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


  def main(args: Array[String]): Unit = {
    logger.info("ClientAuthService started")

    // Usage: <mcpUrl|discover:domain> [clientId|'dcr:auto'] [clientSecret|'-'] [scope] [port]
    // Example: main("discover:https://portola-dev.us.auth0.com", "my-client-id", "my-secret")
    // Example with DCR: main("discover:https://portola-dev.us.auth0.com", "dcr:auto")

    // Parse arguments with defaults for testing
    val mcpUrlArg = if (args.length >= 1) args(0) else "discover:https://portola-dev.us.auth0.com"
    val clientIdArg = if (args.length >= 2) args(1) else "8ZaIuLcpf3fvBh7qH7sLuRjEe1Gy1Yax"
    val clientSecretArg = if (args.length >= 3) args(2) else "muG1iXaizOloSaOhi9uWqdufW19rk_meHzi2B7k1S74aJuWIPGE3Daaf0S5ivvVB"
    val scope = if (args.length >= 4) args(3) else "openid profile email"
    val port = if (args.length >= 5) Try(args(4).toInt).getOrElse(8080) else 8080

    implicit val system: ActorSystem[Nothing] = {
      val conf = com.typesafe.config.ConfigFactory.parseString(
        """
      pekko {
        actor {
          provider = local
        }
        loglevel = "OFF"
      }
      """
      )
      ActorSystem(Behaviors.empty, "client-auth-system", conf)
    }


    // Extract the OAuth server URL
    val mcpUrl = if (mcpUrlArg.startsWith("discover:")) {
      mcpUrlArg.substring("discover:".length)
    } else {
      mcpUrlArg
    }

    // Determine if DCR should be used (clientId is "dcr:auto" or empty)
    val clientId = if (clientIdArg == "dcr:auto" || clientIdArg.isEmpty) {
      None
    } else {
      Some(clientIdArg)
    }

    // Parse client secret (skip if "-" or empty, or if DCR is being used)
    val clientSecret = if (clientSecretArg == "-" || clientSecretArg.isEmpty || clientId.isEmpty) {
      None
    } else {
      Some(clientSecretArg)
    }

    // Use the connect function (block for CLI)
    val connectF = connect(mcpUrl, clientId, clientSecret, scope, port)

    val result = try {
      Await.result(connectF, 180.seconds)
    } catch {
      case t: Throwable =>
        logger.error(s"Connection failed: ${t.getMessage}")
        System.exit(1)
        Left("internal error")
    }

    result match {
      case Right(tokenResponse) =>
        logger.info(s"Successfully obtained tokens:")
        logger.info(s"  Access Token: ${tokenResponse.access_token}")
        tokenResponse.refresh_token.foreach(rt => logger.info(s"  Refresh Token: $rt"))
        System.exit(0)
      case Left(err) =>
        logger.error(s"Connection failed: $err")
        System.exit(1)
    }
  }
}
