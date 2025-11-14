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

package app.dragon.turnstile.auth

import app.dragon.turnstile.auth.AuthCodeFlowActor
import app.dragon.turnstile.auth.ClientOAuthHelper
import app.dragon.turnstile.auth.ClientOAuthHelper.{OpenIdConfigurationResponse, TokenResponse}
import app.dragon.turnstile.config.ApplicationConfig
import app.dragon.turnstile.serializer.TurnstileSerializable
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}

import scala.util.{Failure, Success}

/**
 * AuthCodeFlowActor implements OAuth2 Authorization Code Flow with optional Dynamic Client Registration (DCR).
 *
 * Flow:
 * 1. If clientId exists → authCodeRequest
 * 2. If clientId doesn't exist → lookupWellknown → DCR → authCodeRequest
 * 3. After auth code received → exchange for token
 * 4. completeState prints the token
 */
object AuthCodeFlowActor {
  val TypeKey: EntityTypeKey[Message] = EntityTypeKey[Message]("AuthCodeFlowActor")

  def initSharding(system: ActorSystem[?]): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      val flowId = entityContext.entityId
      AuthCodeFlowActor(flowId)
    })

  def getEntityId(userId: String, flowId: String): String = s"$userId-$flowId"

  // Messages
  sealed trait Message extends TurnstileSerializable

  final case class StartFlow(
    mcpServerUuid: String,
    domain: String,
    clientId: Option[String],
    clientSecret: Option[String],
    replyTo: ActorRef[FlowResponse]
  ) extends Message
  
  final case class GetToken(
    state: String,
    code: String,
    replyTo: ActorRef[FlowResponse],
  ) extends Message

  // Internal messages
  private final case class WellKnownResponse(
    clientDiscoveryResponse: ClientOAuthHelper.OpenIdConfigurationResponse,
    replaysTo: ActorRef[FlowResponse]
  ) extends Message

  private final case class TokenExchangeResponse(
    tokenResponse: ClientOAuthHelper.TokenResponse,
    replyTo: ActorRef[FlowResponse]
  ) extends Message

  private final case class DcrResponse(
    clientDcrResponse: ClientOAuthHelper.DcrResponse,
    replyTo: ActorRef[FlowResponse]
  ) extends Message

  private final case class FlowError(error: String, replyTo: ActorRef[FlowResponse]) extends Message

  // Reply messages
  sealed trait FlowResponse extends TurnstileSerializable

  final case class FlowAuthResponse(
    mcpServerUuid: String,
    loginUrl: String,
    tokenEndpoint: String,
    clientId: String,
    clientSecret: Option[String],
  ) extends FlowResponse

  final case class FlowTokenResponse(
    mcpServerUuid: String,
    accessToken: String,
    refreshToken: String
  ) extends FlowResponse // Change this

  final case class FlowFailed(error: String) extends FlowResponse

  // State data
  final case class FlowData(
    mcpServerUuid: String = "",
    domain: String = "",
    clientId: Option[String] = None,
    clientSecret: Option[String] = None,
    token: Option[TokenResponse] = None,
    wellKnownResponse: Option[OpenIdConfigurationResponse] = None,
  )

  def apply(flowId: String): Behavior[Message] =
    Behaviors.setup { context =>
      context.log.info(s"Starting AuthCodeFlowActor for flow $flowId")
      new AuthCodeFlowActor(context, flowId).initState()
    }
}

class AuthCodeFlowActor(
  context: ActorContext[AuthCodeFlowActor.Message],
  flowId: String
) {
  import AuthCodeFlowActor.*

  implicit val system: ActorSystem[Nothing] = context.system
  
  //val domain: String = "https://portola-dev.us.auth0.com" // Placeholder MCP URL
  //val redirectUrl: String = "http://localhost:8080/callback" // Placeholder redirect URI
  val redirectUrl = ApplicationConfig.auth.getString("client.callback-url")
  val audience = ApplicationConfig.auth.getString("client.audience")
  val scope = "openid profile email offline_access"
  
  /**
   * Initial state: Receives StartFlow message and decides next state based on clientId availability
   */
  def initState(): Behavior[Message] = {
    Behaviors.receiveMessagePartial {
      handleStartFlow()
        .orElse(handleFlowErrorInInit())
    }
  }

  private def handleStartFlow(
  ): PartialFunction[Message, Behavior[Message]] = {
    case StartFlow(mcpServerUuid, domain, clientId, clientSecret, replyTo) =>
      context.log.info(s"[$flowId] Received StartFlow")
      val data = FlowData(
        mcpServerUuid = mcpServerUuid,
        domain = domain,
        clientId = clientId,
        clientSecret = clientSecret,
        token = None,
        wellKnownResponse = None,
      )

      // Always start with well-known lookup to get endpoints

      // pass the actor system explicitly to the ClientAuthService call (it expects an implicit ActorSystem)
      context.pipeToSelf(ClientOAuthHelper.fetchWellKnown(domain)) {
        case Success(Right(discovery)) => WellKnownResponse(discovery, replyTo)
        case Success(Left(error)) => FlowError(s"Well-known lookup failed: ${error.toString}", replyTo)
        case Failure(error) => FlowError(s"Well-known lookup failed: ${error.getMessage}", replyTo)
      }
      
      lookupWellknownInProcessState(data)
  }

  private def handleFlowErrorInInit(): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error, replyTo) =>
      context.log.error(s"[$flowId] Flow error in initState: $error")

      replyTo ! FlowFailed(s"Flow error in initState: $error")

      Behaviors.stopped
  }

  /**
   * State: Looking up well-known configuration
   */
  def lookupWellknownInProcessState(
    data: FlowData
  ): Behavior[Message] = {
    context.log.info(s"[$flowId] In lookupWellknownInProcessState")

    Behaviors.receiveMessagePartial {
      handleWellKnownResponse(data)
        .orElse(handleFlowErrorInWellKnown(data))
    }
  }

  private def handleWellKnownResponse(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case WellKnownResponse(discoveryResponse, replyTo) =>
      context.log.info(s"[$flowId] Received well-known response")
      val updatedData = data.copy(
        wellKnownResponse = Some(discoveryResponse),
      )

      // Decision: if clientId exists, go to authCodeRequest, otherwise do DCR
      updatedData.clientId match {
        case Some(clientId) if clientId.nonEmpty =>
          context.log.info(s"[$flowId] ClientId exists ($clientId), proceeding to auth code request")
          
          val authUrl = ClientOAuthHelper.buildAuthorizationUrl(
            discoveryResponse.authorization_endpoint.getOrElse(""),  // TODO: Handle missing endpoint
            audience,
            clientId,
            redirectUrl,
            scope, //"openid profile email offline_access",
            flowId,
          )
          
          //replyTo ! FlowLoginUrl(authUrl)
          replyTo ! FlowAuthResponse(updatedData.mcpServerUuid, authUrl, discoveryResponse.token_endpoint.getOrElse(""), clientId, updatedData.clientSecret)

          authCodeRequestInProcess(updatedData)

        case _ => // No clientId, proceed to DCR
          discoveryResponse.registration_endpoint match {
            case Some(regEndpoint) =>
              context.log.info(s"[$flowId] No clientId, proceeding to DCR at $regEndpoint")
              // pass actor system explicitly and fix typo (removed stray dot)
              context.pipeToSelf(ClientOAuthHelper.performDCR(regEndpoint, redirectUrl)) {
                case Success(Right(dcrResp)) => DcrResponse(dcrResp, replyTo)
                case Success(Left(error)) => FlowError(s"DCR failed: ${error.toString}", replyTo)
                case Failure(error) => FlowError(s"DCR failed: ${error.getMessage}", replyTo)
              }
              
              dcrRequestInProcessState(updatedData)

            case None =>
              context.log.error(s"[$flowId] No clientId and no registration endpoint available")
              //data.replyTo.foreach(_ ! FlowFailed("No clientId provided and no registration endpoint available for DCR"))
              replyTo ! FlowFailed("No clientId provided and no registration endpoint available for DCR")
              Behaviors.stopped
          }
      }
  }

  private def handleFlowErrorInWellKnown(data: FlowData): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error, replyTo) =>
      context.log.error(s"[$flowId] Well-known lookup failed: $error")
      //data.replyTo.foreach(_ ! FlowFailed(s"Well-known lookup failed: $error"))
      replyTo ! FlowFailed(s"Well-known lookup failed: $error")

      Behaviors.stopped
  }

  /**
   * State: Dynamic Client Registration in process
   */
  def dcrRequestInProcessState(
    data: FlowData
  ): Behavior[Message] = {
    context.log.info(s"[$flowId] In dcrRequestInProcessState")

    // TODO: Make HTTP request to registration endpoint
    // For now, this is a placeholder that waits for DcrResponse

    Behaviors.receiveMessagePartial {
      handleDcrResponse(data)
        .orElse(handleFlowErrorInDcr(data))
    }
  }

  private def handleDcrResponse(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case DcrResponse(clientDcrResponse, replyTo) =>
      context.log.info(s"[$flowId] Received DCR response, clientId: ${clientDcrResponse.client_id}")
      val updatedData = data.copy(
        clientId = clientDcrResponse.client_id,
        clientSecret = clientDcrResponse.client_secret,
      )

      context.log.info(s"[$flowId] DCR complete, proceeding to auth code request")

      // Check to see if redirect URI is in the registered URIs
      clientDcrResponse.redirect_uris.getOrElse(List()).filter(_ == redirectUrl) match {
        case Nil => context.log.error(s"[$flowId] Registered redirect URIs do not include expected redirect URI: $redirectUrl")
        case _ => context.log.debug(s"[$flowId] Redirect URI $redirectUrl is registered")
      }

      val authUrl = ClientOAuthHelper.buildAuthorizationUrl(
        data.wellKnownResponse.get.authorization_endpoint.getOrElse(""),  // TODO: Handle missing endpoint
        audience,
        clientDcrResponse.client_id.getOrElse(""),
        redirectUrl,
        scope, //"openid profile email",
        flowId,
      )

      //replyTo ! FlowLoginUrl(authUrl)
      replyTo ! FlowAuthResponse(updatedData.mcpServerUuid, authUrl, data.wellKnownResponse.get.token_endpoint.getOrElse(""),
        clientDcrResponse.client_id.getOrElse(""), clientDcrResponse.client_secret)

      authCodeRequestInProcess(updatedData)
  }

  private def handleFlowErrorInDcr(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error, replyTo) =>
      context.log.error(s"[$flowId] DCR failed: $error")
      //data.replyTo.foreach(_ ! FlowFailed(s"DCR failed: $error"))
      replyTo ! FlowFailed(s"DCR failed: $error")
      Behaviors.stopped
  }

  /**
   * State: Authorization code request in process
   */
  def authCodeRequestInProcess(
    data: FlowData
  ): Behavior[Message] = {
    context.log.info(s"[$flowId] In authCodeRequestInProcess")
    
    Behaviors.receiveMessagePartial {
      handleAuthCodeResponse(data)
        .orElse(handleFlowErrorInAuthCode(data))
    }
  }

  private def handleAuthCodeResponse(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case GetToken(state, code, replyTo) =>
      context.log.info(s"[$flowId] Received authorization code")

      context.log.info(s"[$flowId] Auth code received, proceeding to token exchange")
      
      // TODO: Verify state matches
      
      context.pipeToSelf(ClientOAuthHelper.exchangeAuthorizationCode(
        data.wellKnownResponse.get.token_endpoint.get,  // TODO: Handle missing token endpoint
        data.clientId.get,  // TODO: Handle missing clientId
        data.clientSecret,
        code,
        redirectUrl,
      )) {
        case Success(Right(tokenResp)) => TokenExchangeResponse(tokenResp, replyTo)
        case Success(Left(error)) => FlowError(s"Token exchange failed: ${error.toString}", replyTo)
        case Failure(error) => FlowError(s"Token exchange failed: ${error.getMessage}", replyTo)
      }

      tokenRequestInProcess(data)
  }

  private def handleFlowErrorInAuthCode(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error, replyTo) =>
      context.log.error(s"[$flowId] Auth code request failed: $error")
      //data.replyTo.foreach(_ ! FlowFailed(s"Auth code request failed: $error"))
      replyTo ! FlowFailed(s"Auth code request failed: $error")

      Behaviors.stopped
  }

  /**
   * State: Token request in process
   */
  def tokenRequestInProcess(
    data: FlowData
  ): Behavior[Message] = {
    context.log.info(s"[$flowId] In tokenRequestInProcess")

    // TODO: Make HTTP POST request to token endpoint to exchange code for token
    // For now, this is a placeholder that waits for TokenResponse

    Behaviors.receiveMessagePartial {
      handleTokenResponse(data)
        .orElse(handleFlowErrorInToken(data))
    }
  }

  private def handleTokenResponse(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case TokenExchangeResponse(tokenResponse, replyTo) =>
      context.log.info(s"[$flowId] Received token response")
      val updatedData = data.copy(token = Some(tokenResponse))

      context.log.info(s"[$flowId] Token received, proceeding to complete state")

      //replyTo ! FlowComplete(tokenResponse.access_token, tokenResponse.refresh_token.getOrElse(""))

      completeState(updatedData, replyTo)
  }

  private def handleFlowErrorInToken(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error, replyTo) =>
      context.log.error(s"[$flowId] Token exchange failed: $error")
      //data.replyTo.foreach(_ ! FlowFailed(s"Token exchange failed: $error"))
      replyTo ! FlowFailed(s"Token exchange failed: $error")

      Behaviors.stopped
  }

  /**
   * Complete state: Prints token and sends response
   */
  def completeState(
    data: FlowData,
    replyTo: ActorRef[FlowResponse]
  ): Behavior[Message] = {
    context.log.info(s"[$flowId] In completeState")

    data.token match {
      case Some(token: TokenResponse) =>
        context.log.info(s"[$flowId] ========== AUTH CODE FLOW COMPLETE ==========")
        context.log.info(s"[$flowId] Access Token: ${token.access_token}")
        //context.log.info(s"[$flowId] Token Type: ${token.tokenType}")
        //token.expiresIn.foreach(exp => context.log.info(s"[$flowId] Expires In: $exp seconds"))
        context.log.info(s"[$flowId] Refresh Token: ${token.refresh_token}")
        //token.scope.foreach(sc => context.log.info(s"[$flowId] Scope: $sc"))
        context.log.info(s"[$flowId] ============================================")

        replyTo ! FlowTokenResponse(data.mcpServerUuid, token.access_token, token.refresh_token)

        //data.replyTo.foreach(_ ! FlowComplete(token))

      case None =>
        context.log.error(s"[$flowId] Complete state reached without token!")
        //data.replyTo.foreach(_ ! FlowFailed("Complete state reached without token"))
        replyTo ! FlowFailed("Complete state reached without token")
    }

    Behaviors.stopped
  }

}
