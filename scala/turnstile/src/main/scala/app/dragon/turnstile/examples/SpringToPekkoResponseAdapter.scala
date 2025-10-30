package app.dragon.turnstile.examples

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpResponse, StatusCode, headers}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders as SpringHeaders
import org.springframework.http.HttpHeaders as SpringHeaders
import org.springframework.http.HttpHeaders as SpringHeaders
import org.springframework.http.server.reactive.ServerHttpResponse
import reactor.core.publisher.Flux

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

/**
 * Adapter that converts a Spring WebFlux ServerHttpResponse to a Pekko HttpResponse.
 */
class SpringToPekkoResponseAdapter()(implicit system: ActorSystem[?], ec: ExecutionContext)
  extends ServerHttpResponse {

  private val logger = LoggerFactory.getLogger(classOf[SpringToPekkoResponseAdapter])
  private val statusCodeRef = new AtomicReference[org.springframework.http.HttpStatusCode](org.springframework.http.HttpStatus.OK)
  private val headersRef = new AtomicReference[SpringHeaders](new SpringHeaders())
  private val responsePromise = Promise[HttpResponse]()
  private val dataBufferFactory = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance

  override def setStatusCode(status: org.springframework.http.HttpStatusCode): Boolean = {
    statusCodeRef.set(status)
    true
  }

  override def getStatusCode(): org.springframework.http.HttpStatusCode = statusCodeRef.get()

  override def getHeaders(): SpringHeaders = headersRef.get()

  override def writeWith(body: org.reactivestreams.Publisher[? <: org.springframework.core.io.buffer.DataBuffer]): reactor.core.publisher.Mono[Void] = {
    // Convert Spring DataBuffer stream to Pekko Source
    val bodySource = Source.fromPublisher(body)
      .map { dataBuffer =>
        val byteBuffer = dataBuffer.asByteBuffer()
        val bytes = new Array[Byte](byteBuffer.remaining())
        byteBuffer.get(bytes)
        org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer)
        ByteString(bytes)
      }

    // Build Pekko HttpResponse
    val statusCode = statusCodeRef.get().value()
    val pekkoStatus = StatusCode.int2StatusCode(statusCode)

    // Convert Spring headers to Pekko headers, but exclude Content-Type and Content-Length (set via entity)
    val pekkoHeaders = headersRef.get().entrySet().asScala.flatMap { entry =>
      val name = entry.getKey
      val values = entry.getValue.asScala
      if (name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Content-Length")) Nil
      else values.map(value => headers.RawHeader(name, value))
    }.toList

    // Determine content type
    val contentType = Option(headersRef.get().getContentType)
      .map(mt => ContentType.parse(mt.toString).toOption.getOrElse(ContentTypes.`application/octet-stream`))
      .getOrElse(ContentTypes.`application/octet-stream`)

    val response = HttpResponse(
      status = pekkoStatus,
      headers = pekkoHeaders,
      entity = HttpEntity(contentType, bodySource)
    )

    responsePromise.success(response)

    reactor.core.publisher.Mono.empty()
  }

  override def writeAndFlushWith(body: org.reactivestreams.Publisher[? <: org.reactivestreams.Publisher[? <: org.springframework.core.io.buffer.DataBuffer]]): reactor.core.publisher.Mono[Void] = {
    writeWith(Flux.from(body).flatMap(p => Flux.from(p)))
  }

  override def setComplete(): reactor.core.publisher.Mono[Void] = {
    if (!responsePromise.isCompleted) {
      // Empty response
      val statusCode = statusCodeRef.get().value()
      val pekkoStatus = StatusCode.int2StatusCode(statusCode)

      // Convert Spring headers to Pekko headers
      val pekkoHeaders = headersRef.get().entrySet().asScala.flatMap { entry =>
        val name = entry.getKey
        val values = entry.getValue.asScala
        values.map(value => headers.RawHeader(name, value))
      }.toList

      val response = HttpResponse(
        status = pekkoStatus,
        headers = pekkoHeaders,
        entity = HttpEntity.Empty
      )

      responsePromise.success(response)
    }
    reactor.core.publisher.Mono.empty()
  }

  override def bufferFactory(): org.springframework.core.io.buffer.DataBufferFactory = dataBufferFactory

  override def getCookies(): org.springframework.util.MultiValueMap[String, org.springframework.http.ResponseCookie] = {
    new org.springframework.util.LinkedMultiValueMap[String, org.springframework.http.ResponseCookie]()
  }

  override def addCookie(cookie: org.springframework.http.ResponseCookie): Unit = {
    // Not implemented for this adapter
  }

  override def beforeCommit(action: java.util.function.Supplier[? <: reactor.core.publisher.Mono[Void]]): Unit = {
    // Not implemented for this adapter
  }

  override def isCommitted(): Boolean = {
    responsePromise.isCompleted
  }

  /**
   * Get the converted Pekko HttpResponse
   */
  def getPekkoResponse(): Future[HttpResponse] = responsePromise.future
}
