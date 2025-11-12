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

package app.dragon.turnstile.actor

import app.dragon.turnstile.auth.ClientOAuthHelper
import app.dragon.turnstile.auth.ClientOAuthHelper.OpenIdConfigurationResponse
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
    domain: String,
    clientId: Option[String],
    clientSecret: Option[String],
    //scope: String,
    replyTo: ActorRef[FlowResponse]
  ) extends Message
  
  final case class GetToken(
    state: String,
    code: String,
    replyTo: ActorRef[FlowResponse],
  ) extends Message

  final case class TokenResponse(
    accessToken: String,
    //tokenType: String,
    //expiresIn: Option[Int],
    refreshToken: Option[String],
    //scope: Option[String]
  ) extends Message

  // Internal messages
  private final case class WellKnownResponse(
    clientDiscoveryResponse: ClientOAuthHelper.OpenIdConfigurationResponse,
    replaysTo: ActorRef[FlowResponse]
  ) extends Message

  private final case class DcrResponse(
    //clientId: String,
    //clientSecret: String,
    clientDcrResponse: ClientOAuthHelper.DcrResponse,
    replyTo: ActorRef[FlowResponse]
  ) extends Message

  final case class FlowError(error: String) extends Message

  // Reply messages
  sealed trait FlowResponse extends TurnstileSerializable
  //final case class FlowLoginUrl(loginUrl: String) extends FlowResponse
  final case class FlowAuthResponse(loginUrl: String, 
    tokenEndpoint: String,
    clientId: String,
    clientSecret: Option[String],
  ) extends FlowResponse
  final case class FlowComplete(token: TokenResponse) extends FlowResponse // Change this
  final case class FlowFailed(error: String) extends FlowResponse

  // State data
  final case class FlowData(
    domain: String = "",
    clientId: Option[String] = None,
    clientSecret: Option[String] = None,
    //redirectUri: String = "",
    //scope: String = "",
    //wellKnownUrl: String = "",

    //authCode: Option[String] = None,
    token: Option[TokenResponse] = None,
    wellKnownResponse: Option[OpenIdConfigurationResponse] = None,
    
    replyTo: Option[ActorRef[FlowResponse]] = None
  )

  def apply(flowId: String): Behavior[Message] =
    Behaviors.setup { context =>
      //implicit val system: ActorSystem[Nothing] = context.system
      //implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

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
  val redirectUrl: String = "http://localhost:8080/callback" // Placeholder redirect URI
  
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
    case msg: StartFlow =>
      context.log.info(s"[$flowId] Received StartFlow")
      val data = FlowData(
        domain = msg.domain,
        clientId = msg.clientId,
        clientSecret = msg.clientSecret,
      )

      // Always start with well-known lookup to get endpoints

      // pass the actor system explicitly to the ClientAuthService call (it expects an implicit ActorSystem)
      context.pipeToSelf(ClientOAuthHelper.fetchWellKnown(msg.domain)) {
        case Success(Right(discovery)) =>
            WellKnownResponse(
              discovery,
              msg.replyTo
            )
        case Success(Left(error)) => FlowError(s"Well-known lookup failed: ${error.toString}")
        case Failure(error) => FlowError(s"Well-known lookup failed: ${error.getMessage}")
      }
      
      lookupWellknownInProcessState(data)
  }

  private def handleFlowErrorInInit(): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error) =>
      context.log.error(s"[$flowId] Flow error in initState: $error")
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
        case Some(clientId) =>
          context.log.info(s"[$flowId] ClientId exists ($clientId), proceeding to auth code request")
          
          val authUrl = ClientOAuthHelper.buildAuthorizationUrl(
            discoveryResponse.authorization_endpoint.getOrElse(""),  // TODO: Handle missing endpoint
            clientId, 
            redirectUrl,
            "openid profile email",
            flowId,
          )
          
          //replyTo ! FlowLoginUrl(authUrl)
          replyTo ! FlowAuthResponse(authUrl, discoveryResponse.token_endpoint.getOrElse(""), clientId, updatedData.clientSecret)
          
          authCodeRequestInProcess(updatedData)

        case None =>
          discoveryResponse.registration_endpoint match {
            case Some(regEndpoint) =>
              context.log.info(s"[$flowId] No clientId, proceeding to DCR at $regEndpoint")
              // TODO: Trigger DCR request
              // pass actor system explicitly and fix typo (removed stray dot)
              context.pipeToSelf(ClientOAuthHelper.performDCR(regEndpoint, redirectUrl)) {
                case Success(Right(dcrResp)) =>
                  DcrResponse(
                    //dcrResp.client_id.getOrElse(""), 
                    //dcrResp.client_secret.getOrElse(""),
                    clientDcrResponse = dcrResp,
                    replyTo,
                  )
                case Success(Left(error)) => FlowError(s"DCR failed: ${error.toString}")
                case Failure(error) => FlowError(s"DCR failed: ${error.getMessage}")
              }
              
              dcrRequestInProcessState(updatedData)

            case None =>
              context.log.error(s"[$flowId] No clientId and no registration endpoint available")
              data.replyTo.foreach(_ ! FlowFailed("No clientId provided and no registration endpoint available for DCR"))
              Behaviors.stopped
          }
      }
  }

  private def handleFlowErrorInWellKnown(data: FlowData): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error) =>
      context.log.error(s"[$flowId] Well-known lookup failed: $error")
      data.replyTo.foreach(_ ! FlowFailed(s"Well-known lookup failed: $error"))
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
        clientDcrResponse.client_id.getOrElse(""),
        redirectUrl,
        "openid profile email",
        flowId,
      )
      
      //replyTo ! FlowLoginUrl(authUrl)
      replyTo ! FlowAuthResponse(authUrl, data.wellKnownResponse.get.token_endpoint.getOrElse(""), 
        clientDcrResponse.client_id.getOrElse(""), clientDcrResponse.client_secret)

      authCodeRequestInProcess(updatedData)
  }

  private def handleFlowErrorInDcr(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error) =>
      context.log.error(s"[$flowId] DCR failed: $error")
      data.replyTo.foreach(_ ! FlowFailed(s"DCR failed: $error"))
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
        case Success(Right(tokenResp)) =>
          TokenResponse(
            accessToken = tokenResp.access_token,
            //tokenType = tokenResp.,
            //expiresIn = tokenResp.expires_in,
            refreshToken = tokenResp.refresh_token,
            //scope = tokenResp.scope,
          )
        case Success(Left(error)) => FlowError(s"Token exchange failed: ${error.toString}")
        case Failure(error) => FlowError(s"Token exchange failed: ${error.getMessage}")
      }
      
       
      
      tokenRequestInProcess(data)
  }

  private def handleFlowErrorInAuthCode(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error) =>
      context.log.error(s"[$flowId] Auth code request failed: $error")
      data.replyTo.foreach(_ ! FlowFailed(s"Auth code request failed: $error"))
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
    case tokenResp: TokenResponse =>
      context.log.info(s"[$flowId] Received token response")
      val updatedData = data.copy(token = Some(tokenResp))

      context.log.info(s"[$flowId] Token received, proceeding to complete state")
      completeState(updatedData)
  }

  private def handleFlowErrorInToken(
    data: FlowData
  ): PartialFunction[Message, Behavior[Message]] = {
    case FlowError(error) =>
      context.log.error(s"[$flowId] Token exchange failed: $error")
      data.replyTo.foreach(_ ! FlowFailed(s"Token exchange failed: $error"))
      Behaviors.stopped
  }

  /**
   * Complete state: Prints token and sends response
   */
  def completeState(
    data: FlowData
  ): Behavior[Message] = {
    context.log.info(s"[$flowId] In completeState")

    data.token match {
      case Some(token) =>
        context.log.info(s"[$flowId] ========== AUTH CODE FLOW COMPLETE ==========")
        context.log.info(s"[$flowId] Access Token: ${token.accessToken}")
        //context.log.info(s"[$flowId] Token Type: ${token.tokenType}")
        //token.expiresIn.foreach(exp => context.log.info(s"[$flowId] Expires In: $exp seconds"))
        token.refreshToken.foreach(rt => context.log.info(s"[$flowId] Refresh Token: $rt"))
        //token.scope.foreach(sc => context.log.info(s"[$flowId] Scope: $sc"))
        context.log.info(s"[$flowId] ============================================")

        data.replyTo.foreach(_ ! FlowComplete(token))

      case None =>
        context.log.error(s"[$flowId] Complete state reached without token!")
        data.replyTo.foreach(_ ! FlowFailed("Complete state reached without token"))
    }

    Behaviors.stopped
  }

}
