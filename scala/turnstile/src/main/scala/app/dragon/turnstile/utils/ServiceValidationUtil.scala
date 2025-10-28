package app.dragon.turnstile.utils

import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

object ServiceValidationUtil {
  /**
   * Validates that a string is not empty. If empty, returns a failed Future with INVALID_ARGUMENT.
   * Otherwise, returns a successful Future with the value.
   *
   * @param value The string to validate
   * @param fieldName The name of the field (for error messages)
   * @param ec ExecutionContext for the Future
   * @return Future[String] either successful or failed with INVALID_ARGUMENT
   */
  def validateNotEmpty(value: String, fieldName: String)(implicit ec: ExecutionContext): Future[String] = {
    if (value.isEmpty) {
      Future.failed(Status.INVALID_ARGUMENT.withDescription(s"$fieldName cannot be empty").asRuntimeException())
    } else {
      Future.successful(value)
    }
  }
}

