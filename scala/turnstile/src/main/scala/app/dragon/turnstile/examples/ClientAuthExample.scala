/*
 * Copyright 2025 Sami Malik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: Sami Malik (sami.malik [at] portolanetwork.io)
 */

package app.dragon.turnstile.examples

import app.dragon.turnstile.auth.AuthCodeFlowActor.*
import app.dragon.turnstile.auth.AuthCodeFlowActor
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import java.awt.Desktop
import java.io.OutputStream
import java.net.{InetSocketAddress, URI}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Example demonstrating how to use AuthCodeFlowActor to perform OAuth2 authorization code flow.
 *
 * This example:
 * 1. Creates a local ActorSystem
 * 2. Spawns an AuthCodeFlowActor
 * 3. Starts a local callback server to receive the auth code
 * 4. Initiates the OAuth flow with a predefined domain and client credentials
 * 5. Opens the browser for user authentication
 * 6. Exchanges the auth code for access tokens
 * 7. Prints the tokens to the console
 */
object ClientAuthExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("ClientAuthExample Started")

    // Test configuration from ClientAuthService.main
    val domain = "https://portola-dev.us.auth0.com"
    val clientId = None//Some("8ZaIuLcpf3fvBh7qH7sLuRjEe1Gy1Yax")
    val clientSecret = None//Some("muG1iXaizOloSaOhi9uWqdufW19rk_meHzi2B7k1S74aJuWIPGE3Daaf0S5ivvVB")
    val scope = "openid profile email"
    val callbackPort = 8080

    // Create a minimal ActorSystem for testing
    implicit val system: ActorSystem[Nothing] = {
      val conf = com.typesafe.config.ConfigFactory.parseString(
        """
        pekko {
          actor {
            provider = local
            serialization-bindings {
              "app.dragon.turnstile.serializer.TurnstileSerializable" = jackson-json
            }
          }
          loglevel = "INFO"
        }
        """
      )
      ActorSystem(Behaviors.empty, "auth-example-system", conf)
    }
    implicit val ec = system.executionContext

    try {
      val flowId = java.util.UUID.randomUUID().toString
      logger.info(s"Starting auth flow with flowId: $flowId")

      // Create a promise to capture the flow response
      val responsePromise = scala.concurrent.Promise[FlowResponse]()

      // Spawn a simple actor to receive the response
      val replyToActor = system.systemActorOf(
        Behaviors.receive[FlowResponse] { (context, response) =>
          logger.info(s"Received response: $response")
          response match {
            case FlowAuthResponse(mcpServerUuid, loginUrl, tokenEndpoint, clientId, clientSecret) =>
              logger.info(s"Login URL: $loginUrl")
              logger.info("Opening browser...")

              // Open the browser
              if (Desktop.isDesktopSupported) {
                Desktop.getDesktop.browse(new URI(loginUrl))
              } else {
                logger.info(s"Please open this URL in your browser: $loginUrl")
              }

            case FlowTokenResponse(mcpServerUuid, accessToken, refreshToken) =>
              logger.info("========== FLOW COMPLETE ==========")
              logger.info(s"Access Token: ${accessToken}")
              //token.refreshToken.foreach(rt => logger.info(s"Refresh Token: $rt"))
              logger.info(s"Refresh Token: ${refreshToken}")
              logger.info("===================================")
              responsePromise.success(response)

            case FlowFailed(error) =>
              logger.error(s"Flow failed: $error")
              responsePromise.failure(new RuntimeException(error))
          }
          Behaviors.same
        },
        "flow-response-handler"
      )

      // Spawn the auth flow actor directly (no sharding needed for testing)
      val authFlowActor = system.systemActorOf(
        AuthCodeFlowActor(flowId),
        s"auth-flow-$flowId"
      )

      // Start a callback server to receive the auth code
      val server = HttpServer.create(new InetSocketAddress("0.0.0.0", callbackPort), 0)
      server.createContext("/callback", (exchange: HttpExchange) => {
        try {
          val query = Option(exchange.getRequestURI.getQuery).getOrElse("")
          val params = parseCallbackQuery(query)

          logger.info(s"Received callback with params: $params")

          val response = "<html><body><h1>Authorization received</h1><p>You can close this window.</p></body></html>"
          val bytes = response.getBytes("UTF-8")
          exchange.getResponseHeaders.add("Content-Type", "text/html; charset=UTF-8")
          exchange.sendResponseHeaders(200, bytes.length)
          val os: OutputStream = exchange.getResponseBody
          os.write(bytes)
          os.close()

          // Send GetToken message to the actor
          params.get("code").foreach { code =>
            val state = params.getOrElse("state", "")
            authFlowActor ! GetToken(state, code, replyToActor)
          }
        } catch {
          case t: Throwable =>
            logger.error("Error handling callback", t)
        }
      })
      server.setExecutor(null)
      server.start()
      logger.info(s"Callback server started on port $callbackPort")

      // Start the flow
      val mcpServerUuid = java.util.UUID.randomUUID().toString
      authFlowActor ! StartFlow(mcpServerUuid, domain, clientId, clientSecret, replyToActor)

      // Wait for the flow to complete (with timeout)
      val result = Await.result(responsePromise.future, 180.seconds)

      logger.info("Example completed successfully")
      server.stop(0)
      system.terminate()
      Await.result(system.whenTerminated, 10.seconds)
      System.exit(0)

    } catch {
      case t: Throwable =>
        logger.error("Example failed", t)
        system.terminate()
        System.exit(1)
    }
  }

  private def parseCallbackQuery(query: String): Map[String, String] = {
    if (query.isEmpty) Map.empty
    else {
      query.split("&").toList.flatMap { part =>
        part.split("=", 2) match {
          case Array(k, v) =>
            Some(java.net.URLDecoder.decode(k, "UTF-8") -> java.net.URLDecoder.decode(v, "UTF-8"))
          case Array(k) =>
            Some(java.net.URLDecoder.decode(k, "UTF-8") -> "")
          case _ => None
        }
      }.toMap
    }
  }
}
