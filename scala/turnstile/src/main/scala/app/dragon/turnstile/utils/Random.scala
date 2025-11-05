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

import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, UUID}
import java.util.concurrent.CompletableFuture
import scala.concurrent.{ExecutionContext, Future}

object Random {
  def generateRandBase64String(length: Int): String = {
    val random = new SecureRandom()
    val bytes = new Array[Byte](length)
    random.nextBytes(bytes)
    //Base64.getEncoder.encodeToString(bytes)
    Base64.getUrlEncoder.encodeToString(bytes)
  }

  def toScalaFuture[T](cf: CompletableFuture[T])(implicit ec: ExecutionContext): Future[T] =
    Future {
      cf.get()
    }

  def urlSafeHash(str: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(str.getBytes("UTF-8"))
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest)
  }
  
  def generateUuid(): String = {
    UUID.randomUUID().toString
  }
}


