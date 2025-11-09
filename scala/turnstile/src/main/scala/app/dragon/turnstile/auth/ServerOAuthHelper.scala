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

import app.dragon.turnstile.config.ApplicationConfig
import com.auth0.jwk.UrlJwkProvider
import pdi.jwt.{JwtAlgorithm, JwtBase64, JwtClaim, JwtJson}

import java.time.Clock
import scala.util.{Failure, Success, Try}

/**
 * Authentication service for validating JWT tokens.
 *
 * This service validates JWT tokens from Auth0, verifying the signature,
 * issuer, audience, and expiration claims.
 */
object ServerOAuthHelper {

  // A regex that defines the JWT pattern and allows us to
  // extract the header, claims and signature
  private val jwtRegex = """(.+?)\.(.+?)\.(.+?)""".r

  private def domain = ApplicationConfig.auth.getString("auth0.domain")
  private def audience = ApplicationConfig.auth.getString("auth0.audience")
  private def issuer = s"https://$domain/"

  // Validates a JWT and potentially returns the claims if the token was successfully parsed
  def validateJwt(
    token: String
  ): Try[JwtClaim] = {
    for {
      jwk <- getJwk(token)           // Get the secret key for this token
      claims <- JwtJson.decode(token, jwk.getPublicKey, Seq(JwtAlgorithm.RS256)) // Decode the token using the secret key
      _ <- validateClaims(claims)     // validate the data stored inside the token
    } yield claims
  }

  /**
   * Extracts the user ID from JWT claims.
   * Typically this is the 'sub' (subject) claim in Auth0 tokens.
   */
  def getUserIdFromClaims(
    claims: JwtClaim
  ): Option[String] = {
    claims.subject
  }

  /**
   * Extracts custom claims from the JWT token.
   * Auth0 custom claims are typically namespaced.
   */
  def getCustomClaim(
    claims: JwtClaim,
    key: String
  ): Option[String] = {
    import play.api.libs.json._
    Try {
      val json = Json.parse(claims.content)
      (json \ key).asOpt[String]
    }.toOption.flatten
  }

  // Splits a JWT into it's 3 component parts
  private def splitToken(
    jwt: String
  ): Try[(String, String, String)] = jwt match {
    case jwtRegex(header, body, sig) => Success((header, body, sig))
    case _ => Failure(new Exception("Token does not match the correct pattern"))
  }

  // As the header and claims data are base64-encoded, this function
  // decodes those elements
  private def decodeElements(
    data: Try[(String, String, String)]
  ): Try[(String, String, String)] = data map {
    case (header, body, sig) => (JwtBase64.decodeString(header), JwtBase64.decodeString(body), sig)
  }

  // Gets the JWK from the JWKS endpoint using the jwks-rsa library
  private def getJwk(
    token: String
  ): Try[com.auth0.jwk.Jwk] = {
    (splitToken andThen decodeElements)(token) flatMap {
      case (header, _, _) =>
        val jwtHeader = JwtJson.parseHeader(header)     // extract the header
        val jwkProvider = new UrlJwkProvider(s"https://$domain")

        // Use jwkProvider to load the JWKS data and return the JWK
        jwtHeader.keyId.map { k =>
          Try(jwkProvider.get(k))
        } getOrElse Failure(new Exception("Unable to retrieve kid"))
    }
  }

  given clock: Clock = Clock.systemUTC

  // Validates the claims inside the token. isValid checks the issuedAt, expiresAt,
  // issuer and audience fields.
  private def validateClaims(
    claims: JwtClaim
  ): Try[JwtClaim] = {
    if (claims.isValid(issuer, audience)(using clock)) {
      Success(claims)
    } else {
      Failure(new Exception("The JWT did not pass validation"))
    }
  }

}
