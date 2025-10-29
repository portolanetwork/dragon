package app.dragon.turnstile.utils

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
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
}


