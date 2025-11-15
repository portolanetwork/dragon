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

package app.dragon.turnstile.utils

import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

object ServiceValidationUtil {


  //private val authService = new AuthService()

  /**
   * Validates that a string is not empty. If empty, returns a failed Future with INVALID_ARGUMENT.
   * Otherwise, returns a successful Future with the value.
   */
  def validateNotEmpty(
    value: String,
    fieldName: String
  )(
    implicit ec: ExecutionContext
  ): Future[String] = {
    if (value.isEmpty) {
      Future.failed(
        Status.INVALID_ARGUMENT
          .withDescription(s"$fieldName cannot be empty")
          .asRuntimeException())
    } else {
      Future.successful(value)
    }
  }

  def validateHasNoSpaces(
    value: String,
    fieldName: String
  )(implicit ec: ExecutionContext): Future[String] = {
    if (value.contains(" ")) {
      Future.failed(
        Status.INVALID_ARGUMENT
          .withDescription(s"$fieldName cannot contain spaces")
          .asRuntimeException())
    } else {
      Future.successful(value)
    }
  }

  def validateEquals(
    value1: String,
    value2: String,
    message: String,
  )(
    implicit ec: ExecutionContext
  ): Future[Unit] = {
    if (value1 != value2) {
      Future.failed(
        Status.INVALID_ARGUMENT
          .withDescription(s"$message: '$value1' != '$value2'")
          .asRuntimeException())
    } else {
      Future.successful(())
    }
  }

}
