package app.dragon.turnstile.auth

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.circe.*
import io.circe.generic.auto.*
import org.slf4j.LoggerFactory
import sttp.client4.*
import sttp.client4.circe.*
import sttp.client4.httpclient.HttpClientSyncBackend

import java.awt.Desktop
import java.io.OutputStream
import java.net.{InetSocketAddress, URI}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.Try

object ClientAuthService {
  private val logger = LoggerFactory.getLogger(getClass)

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
   * @return Either an error message or TokenResponse with access and refresh tokens
   */
  def connect(
    mcpUrl: String,
    clientId: Option[String],
    clientSecret: Option[String],
    scope: String = "openid profile email",
    redirectPort: Int = 8080
  ): Either[String, TokenResponse] = {
    logger.info(s"Connecting to OAuth server at $mcpUrl")

    // Discover endpoints
    val discovered = fetchWellKnown(mcpUrl) match {
      case Right(m) => m
      case Left(err) => return Left(s"Failed to fetch well-known configuration from $mcpUrl: $err")
    }

    val authorizationEndpoint = discovered.get("authorization_endpoint") match {
      case Some(v) => v
      case None => return Left("well-known document did not contain authorization_endpoint")
    }

    // token endpoint is required by current flow
    var tokenEndpoint: Option[String] = discovered.get("token_endpoint") match {
      case Some(v) => Some(v)
      case None => return Left("well-known document did not contain token_endpoint")
    }

    var finalClientId = clientId.getOrElse("")
    var finalClientSecret = clientSecret

    // Optional Dynamic Client Registration
    if (clientId.isEmpty) {
      val registrationEndpoint = discovered.get("registration_endpoint") match {
        case Some(v) => v
        case None => return Left("DCR requested but no registration_endpoint found in discovery document")
      }
      val redirectUri = s"http://localhost:${redirectPort}/callback"
      performDynamicClientRegistration(registrationEndpoint, redirectUri, Map.empty) match {
        case Left(err) => return Left(s"Dynamic client registration failed: $err")
        case Right(dcrResp) =>
          finalClientId = dcrResp.getOrElse("client_id", "")
          if (finalClientId.isEmpty) return Left("DCR response did not contain client_id")
          finalClientSecret = dcrResp.get("client_secret").orElse(finalClientSecret)
          tokenEndpoint = dcrResp.get("token_endpoint").orElse(tokenEndpoint)
          logger.info(s"Successfully registered client with ID: $finalClientId")
      }
    }

    if (finalClientId.trim.isEmpty) return Left("client_id is empty — provide a client id or omit it to use dynamic registration")

    val cfg = Config(
      clientId = finalClientId,
      clientSecret = finalClientSecret,
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
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

  // Perform dynamic client registration (DCR) by POSTing JSON metadata to the registration endpoint.
  // Returns a map of string fields (e.g., client_id, client_secret) on success.
  private def performDynamicClientRegistration(
    registrationEndpoint: String,
    redirectUri: String,
    metadata: Map[String, String]
  ): Either[String, Map[String, String]] = {
    Try {
      val backend = HttpClientSyncBackend()

      // Build JSON body using circe to ensure correctness
      val bodyJson = Json.obj(
        ("client_name", Json.fromString("turnstile-client")),
        ("redirect_uris", Json.arr(Json.fromString(redirectUri))),
        ("grant_types", Json.arr(Json.fromString("authorization_code"))),
        ("token_endpoint_auth_method", Json.fromString("client_secret_basic"))
      )

      val request = basicRequest
        .post(uri"$registrationEndpoint")
        .contentType("application/json")
        .acceptEncoding("utf-8")
        .body(bodyJson.noSpaces)
        .response(asJson[DcrResponse])

      val response = request.send(backend)
      backend.close()

      (response.code.code, response.body)
    }.toEither.left.map(_.getMessage).flatMap {
      case (status: Int, bodyEither: Either[ResponseException[String], DcrResponse]) =>
        if (status >= 200 && status < 300) {
          bodyEither match {
            case Right(d) =>
              val out = scala.collection.mutable.Map.empty[String, String]
              d.client_id.foreach(v => out.put("client_id", v))
              d.client_secret.foreach(v => out.put("client_secret", v))
              d.registration_client_uri.foreach(v => out.put("registration_client_uri", v))
              d.token_endpoint.foreach(v => out.put("token_endpoint", v))
              Right(out.toMap)
            case Left(err) => Left(s"Failed to parse DCR response: ${err.getMessage}")
          }
        } else {
          Left(s"HTTP $status")
        }
    }
  }

   // Fetch a well-known OpenID Connect configuration and extract string-valued keys into a simple Map.
   // This is a lightweight parser (no JSON dependency): it extracts string fields only.
   private def fetchWellKnown(
     domain: String
   ): Either[String, Map[String, String]] = {
      Try {
        val base = if (domain.startsWith("http://") || domain.startsWith("https://")) domain else s"https://$domain"
        val urlStr = if (base.contains(".well-known")) base else s"${base.stripSuffix("/")}/.well-known/openid-configuration"

        val backend = HttpClientSyncBackend()
        val request = basicRequest.get(uri"$urlStr").acceptEncoding("utf-8").response(asJson[Discovery])
        val response = request.send(backend)
        backend.close()
        (response.code.code, response.body)
      }.toEither.left.map(_.getMessage).flatMap {
        case (status: Int, bodyEither: Either[ResponseException[String], Discovery]) =>
          if (status >= 200 && status < 300) {
            bodyEither match {
              case Right(d) =>
                val m = scala.collection.mutable.Map.empty[String, String]
                d.authorization_endpoint.foreach(v => m.put("authorization_endpoint", v))
                d.token_endpoint.foreach(v => m.put("token_endpoint", v))
                d.registration_endpoint.foreach(v => m.put("registration_endpoint", v))
                Right(m.toMap)
              case Left(err) => Left(s"Failed to parse discovery document: ${err.getMessage}")
            }
          } else Left(s"HTTP $status")
      }
   }

   private def printUsage(): Unit = {
     val usage =
       """
         |Usage: ClientAuthService <clientId> <authorizationEndpoint|discover:domain> <tokenEndpoint|'none'> [clientSecret|'-'] [scope] [port]
         |
         |Example:
         |  ClientAuthService my-client-id discover:auth.example.com none my-secret "openid profile" 8080
         |
         |If the token endpoint is 'none' or '-', the flow will only retrieve the authorization code and will not attempt an exchange.
         |Client secret may be '-' if not applicable.
         |""".stripMargin
     println(usage)
   }

   private def runAuthCodeFlow(
     cfg: Config
   ): Either[String, Option[TokenResponse]] = {
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
                 exchangeAuthorizationCode(tokenUrl, cfg.clientId, cfg.clientSecret, code, redirectUri)
                   .left.map(err => s"Token exchange failed: $err").map(Some(_))
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
   ): Either[String, TokenResponse] = {
     Try {
       val backend = HttpClientSyncBackend()

       val baseForm = Map(
         "grant_type" -> "authorization_code",
         "code" -> code,
         "redirect_uri" -> redirectUri
       )

       val formWithClient = clientSecretOpt match {
         case Some(secret) => baseForm
         case None => baseForm + ("client_id" -> clientId)
       }

       var request = basicRequest
         .post(uri"$tokenUrl")
         .contentType("application/x-www-form-urlencoded")
         .response(asJson[TokenResponse])

       // Use HTTP Basic auth if secret provided
       request = clientSecretOpt match {
         case Some(secret) => request.auth.basic(clientId, secret).body(formWithClient)
         case None => request.body(formWithClient)
       }

       val response = request.send(backend)
       backend.close()

       if (response.code.code < 200 || response.code.code >= 300) {
         Left(s"HTTP ${response.code.code}: ${response.statusText}")
       } else {
         response.body.left.map(err => s"Failed to parse token response: ${err.getMessage}")
       }
     }.toEither.left.map(_.getMessage).flatMap(identity)
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

    // Use the connect function
    connect(mcpUrl, clientId, clientSecret, scope, port) match {
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
