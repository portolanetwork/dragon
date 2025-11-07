package app.dragon.turnstile.auth

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.slf4j.LoggerFactory

import java.awt.Desktop
import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.{HttpURLConnection, InetSocketAddress, URI, URL}
import java.util.Base64
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

  def main(args: Array[String]): Unit = {
    logger.info("ClientAuthService started")

    //if (args.contains("-h") || args.contains("--help") || args.length < 3) {
    //  printUsage()
    //  System.exit(0)
    //}

    // Minimal argument parsing: required: clientId authorizationEndpoint tokenEndpoint(optional '-')
    // Usage: <clientId> <authorizationEndpoint|discover:domain> <tokenEndpoint|'none'> [clientSecret] [scope] [port]
    var clientId = "8ZaIuLcpf3fvBh7qH7sLuRjEe1Gy1Yax"//if (args.length >= 1) args(0) else ""
    //var clientId = "dcr:auto"//if (args.length >= 1) args(0) else ""
    val authArg = "discover:https://portola-dev.us.auth0.com"//if (args.length >= 2) args(1) else ""
    var clientSecretX = "muG1iXaizOloSaOhi9uWqdufW19rk_meHzi2B7k1S74aJuWIPGE3Daaf0S5ivvVB"
    //val authArg = "discover:auto"//if (args.length >= 2) args(1) else ""
    //val tokenEndpointArg = if (args.length >= 3) args(2) else "none"
    var authorizationEndpoint = ""//authArg
    var tokenEndpoint: Option[String] = None//if (tokenEndpointArg == "none" || tokenEndpointArg == "-") None else Some(tokenEndpointArg)

    // parse optional values early (we need port/redirect URI for DCR)
    val clientSecretArg = if (args.length >= 4) args(3) else ""
    val scope = if (args.length >= 5) args(4) else "openid profile email"
    val port = if (args.length >= 6) Try(args(5).toInt).getOrElse(8080) else 8080
    val clientSecretProvided = if (clientSecretArg != "-" && clientSecretArg.nonEmpty) Some(clientSecretArg) else None

    // Support discovery: pass authorization argument as discover:domain.example (or discover:https://domain...)
    var discoveredMap: Map[String, String] = Map.empty
    if (authArg.startsWith("discover:")) {
      val domain = authArg.substring("discover:".length)
      fetchWellKnown(domain) match {
        case Left(err) =>
          logger.error(s"Failed to fetch well-known configuration from $domain: $err")
          System.exit(1)
        case Right(map) =>
          discoveredMap = map
          authorizationEndpoint = map.getOrElse("authorization_endpoint", {
            logger.error("well-known document did not contain authorization_endpoint")
            System.exit(1); ""
          })
          tokenEndpoint = map.get("token_endpoint").orElse(tokenEndpoint)
      }
    }

    // Dynamic Client Registration (DCR): if the caller set clientId to "dcr:auto" and the well-known document provides a registration_endpoint,
    // perform registration to obtain a client_id and client_secret automatically.
    if (clientId == "dcr:auto") {
      val regOpt = discoveredMap.get("registration_endpoint")
      if (regOpt.isEmpty) {
        logger.error("DCR requested (clientId=dcr:auto) but no registration_endpoint found in discovery document")
        System.exit(1)
      }
      val registrationEndpoint = regOpt.get
      val redirectUri = s"http://localhost:${port}/callback"
      // Perform a minimal registration request (we build a small JSON body in the helper)
      performDynamicClientRegistration(registrationEndpoint, redirectUri, Map.empty) match {
        case Left(err) =>
          logger.error(s"Dynamic client registration failed: $err")
          System.exit(1)
        case Right(resp) =>
          // adopt client_id returned by the server
          resp.get("client_id").foreach(id => clientId = id)
          // prefer secret returned by DCR unless a clientSecretArg explicitly provided
          val clientSecretFromDcr = resp.get("client_secret")
          val clientSecretFinal = clientSecretProvided.orElse(clientSecretFromDcr)
          // update tokenEndpoint if registration response contains it, else keep discovered/token provided
          tokenEndpoint = tokenEndpoint.orElse(resp.get("token_endpoint")).orElse(discoveredMap.get("token_endpoint"))
          // merge DCR response into discoveredMap so downstream code can read client_secret if needed
          discoveredMap = discoveredMap ++ resp
          // if DCR returned a secret and caller didn't provide one, prefer the DCR secret
          // (we'll compute final clientSecret below)
      }
    }

    // We'll determine the final clientSecret to use: prefer provided arg, else discovered/DCR result in discoveredMap
    val clientSecret = clientSecretProvided.orElse(discoveredMap.get("client_secret")).orElse(Some(clientSecretX))

    val cfg = Config(
      clientId = clientId,
      clientSecret = clientSecret,
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
      scope = scope,
      redirectPort = port
    )

    runAuthCodeFlow(cfg) match {
      case Right(tokenResponseOpt) =>
        tokenResponseOpt match {
          case Some(tokenResp) => logger.info(s"Token response:\n$tokenResp")
          case None => logger.info("Authorization code obtained but token endpoint not configured; flow finished.")
        }
        System.exit(0)
      case Left(err) =>
        logger.error(s"Authorization flow failed: $err")
        System.exit(1)
    }
  }

  // Perform dynamic client registration (DCR) by POSTing JSON metadata to the registration endpoint.
  // Returns a map of string fields (e.g., client_id, client_secret) on success.
  private def performDynamicClientRegistration(registrationEndpoint: String, redirectUri: String, metadata: Map[String, String]): Either[String, Map[String, String]] = {
    Try {
      val url = new URL(registrationEndpoint)
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod("POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Accept", "application/json")

      // Build a minimal JSON body. We expect metadata keys like "redirect_uris[0]" and "grant_types[0]" to be present.
      val redirectUris = Seq(redirectUri)
      val redirectJson = redirectUris.map(u => s"\"$u\"").mkString("[", ",", "]")
      val jsonBody = new StringBuilder
      jsonBody.append("{")
      jsonBody.append("\"client_name\":\"turnstile-client\",")
      jsonBody.append("\"redirect_uris\":" + redirectJson + ",")
      jsonBody.append("\"grant_types\":[\"authorization_code\"],")
      jsonBody.append("\"token_endpoint_auth_method\":\"client_secret_basic\"")
      jsonBody.append("}")

      val bytes = jsonBody.toString().getBytes("UTF-8")
      conn.getOutputStream.write(bytes)
      conn.getOutputStream.close()

      val status = conn.getResponseCode
      val is = if (status >= 200 && status < 300) conn.getInputStream else conn.getErrorStream
      val reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))
      val sb = new StringBuilder
      var line: String = null
      while ({ line = reader.readLine(); line != null }) sb.append(line)
      reader.close()
      val resp = sb.toString()

      // Extract common string fields from JSON response
      val clientIdPattern = """"client_id"\s*:\s*"([^\"]+)"""".r
      val clientSecretPattern = """"client_secret"\s*:\s*"([^\"]+)"""".r
      val registrationUriPattern = """"registration_client_uri"\s*:\s*"([^\"]+)"""".r

      val mClientId = clientIdPattern.findFirstMatchIn(resp).map(_.group(1))
      val mClientSecret = clientSecretPattern.findFirstMatchIn(resp).map(_.group(1))
      val mRegistrationUri = registrationUriPattern.findFirstMatchIn(resp).map(_.group(1))

      val out = scala.collection.mutable.Map.empty[String, String]
      mClientId.foreach(v => out.put("client_id", v))
      mClientSecret.foreach(v => out.put("client_secret", v))
      mRegistrationUri.foreach(v => out.put("registration_client_uri", v))

      (status, out.toMap)
    }.fold[Either[String, Map[String, String]]](t => Left(t.getMessage), {
      case (status: Int, map: Map[String, String]) =>
        if (status >= 200 && status < 300) Right(map) else Left(s"HTTP $status")
    })
  }

   // Fetch a well-known OpenID Connect configuration and extract string-valued keys into a simple Map.
   // This is a lightweight parser (no JSON dependency): it extracts string fields only.
   private def fetchWellKnown(domain: String): Either[String, Map[String, String]] = {
     Try {
       val base = if (domain.startsWith("http://") || domain.startsWith("https://")) domain else s"https://$domain"
       val urlStr = if (base.contains(".well-known")) base else s"${base.stripSuffix("/")}/.well-known/openid-configuration"
       val url = new URL(urlStr)
       val conn = url.openConnection().asInstanceOf[HttpURLConnection]
       conn.setRequestMethod("GET")
       conn.setRequestProperty("Accept", "application/json")
       conn.setConnectTimeout(5000)
       conn.setReadTimeout(5000)

       val status = conn.getResponseCode
       val is = if (status >= 200 && status < 300) conn.getInputStream else conn.getErrorStream
       val reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))
       val sb = new StringBuilder
       var line: String = null
       while ({ line = reader.readLine(); line != null }) sb.append(line)
       reader.close()
       val resp = sb.toString()

       // crude JSON string-field extractor: "key" : "value"
       val pattern = "\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"".r
       val map = pattern.findAllMatchIn(resp).map(m => m.group(1) -> m.group(2)).toMap
       (status, map)
     }.fold(t => Left(t.getMessage), { case (status, map) =>
       if (status >= 200 && status < 300) Right(map) else Left(s"HTTP $status")
     })
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

   private def runAuthCodeFlow(cfg: Config): Either[String, Option[String]] = {
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

         val flowResult: Either[String, Option[String]] =
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

   private def buildAuthorizationUrl(authEndpoint: String, clientId: String, redirectUri: String, scope: String, state: String): String = {
     val params = Map(
       "response_type" -> "code",
       "client_id" -> clientId,
       "redirect_uri" -> redirectUri,
       "scope" -> scope,
       "state" -> state
     ).map { case (k, v) => s"${urlEncode(k)}=${urlEncode(v)}" }.mkString("&")
     if (authEndpoint.contains("?")) s"$authEndpoint&$params" else s"$authEndpoint?$params"
   }

   private def urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

   private def openInBrowser(url: String): Unit = {
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

   private def startCallbackServer(port: Int, path: String, onParams: Map[String, String] => Unit): HttpServer = {
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

   private def parseQuery(query: String): Map[String, String] = {
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

   private def exchangeAuthorizationCode(tokenUrl: String, clientId: String, clientSecretOpt: Option[String], code: String, redirectUri: String): Either[String, String] = {
     Try {
       val url = new URL(tokenUrl)
       val conn = url.openConnection().asInstanceOf[HttpURLConnection]
       conn.setRequestMethod("POST")
       conn.setDoOutput(true)
       conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
       conn.setRequestProperty("Accept", "application/json")

       clientSecretOpt.foreach { secret =>
         val auth = s"$clientId:$secret"
         val encodedAuth = Base64.getEncoder.encodeToString(auth.getBytes("UTF-8"))
         conn.setRequestProperty("Authorization", s"Basic $encodedAuth")
       }

       val body = s"grant_type=authorization_code&code=${urlEncode(code)}&redirect_uri=${urlEncode(redirectUri)}"
       val bytes = body.getBytes("UTF-8")
       conn.getOutputStream.write(bytes)
       conn.getOutputStream.close()

       val status = conn.getResponseCode
       val is = if (status >= 200 && status < 300) conn.getInputStream else conn.getErrorStream
       val reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))
       val sb = new StringBuilder
       var line: String = null
       while ({ line = reader.readLine(); line != null }) sb.append(line)
       reader.close()
       val resp = sb.toString()

       if (status < 200 || status >= 300) Left(s"HTTP $status: $resp")
       else {
         val tokenPattern = "\"access_token\"\\s*:\\s*\"([^\"]+)\"".r
         tokenPattern.findFirstMatchIn(resp).map(_.group(1)).toRight("Access token not found in response")
       }
     }.toEither.left.map(_.getMessage).flatMap(identity)
   }
 }
