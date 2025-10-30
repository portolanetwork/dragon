package app.dragon.turnstile.server

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest}
import org.apache.pekko.stream.scaladsl.Sink
import org.slf4j.LoggerFactory
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.HttpHeaders as SpringHeaders
import org.springframework.http.{HttpMethod, HttpHeaders as SpringHeaders}
import reactor.core.publisher.Flux

import java.net.{InetSocketAddress, URI}
import scala.concurrent.ExecutionContext

/**
 * Adapter that converts a Pekko HttpRequest to a Spring WebFlux ServerHttpRequest.
 */
class PekkoToSpringRequestAdapter(pekkoRequest: HttpRequest)(implicit system: ActorSystem[?], ec: ExecutionContext)
  extends ServerHttpRequest {

  private val logger = LoggerFactory.getLogger(classOf[PekkoToSpringRequestAdapter])

  override def getMethod(): HttpMethod = {
    pekkoRequest.method.name() match {
      case "GET" => HttpMethod.GET
      case "POST" => HttpMethod.POST
      case "PUT" => HttpMethod.PUT
      case "DELETE" => HttpMethod.DELETE
      case "PATCH" => HttpMethod.PATCH
      case "OPTIONS" => HttpMethod.OPTIONS
      case "HEAD" => HttpMethod.HEAD
      case other => HttpMethod.valueOf(other)
    }
  }

  override def getURI(): URI = {
    new URI(pekkoRequest.uri.toString())
  }

  override def getHeaders(): SpringHeaders = {
    val headers = new SpringHeaders()
    pekkoRequest.headers.foreach { header =>
      headers.add(header.name(), header.value())
    }
    // Add Content-Type from entity if present
    pekkoRequest.entity.contentType match {
      case ContentTypes.NoContentType =>
      case contentType =>
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(contentType.toString()))
    }
    headers
  }

  override def getBody(): Flux[org.springframework.core.io.buffer.DataBuffer] = {
    val dataBufferFactory = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance

    // Convert Pekko entity to Flux of DataBuffer
    pekkoRequest.entity match {
      case HttpEntity.Strict(_, data) =>
        if (data.isEmpty) {
          Flux.empty()
        } else {
          Flux.just(dataBufferFactory.wrap(data.toByteBuffer))
        }
      case HttpEntity.Default(_, _, dataStream) =>
        val publisher = dataStream
          .map(byteString => dataBufferFactory.wrap(byteString.toByteBuffer))
          .runWith(Sink.asPublisher(fanout = false))
        Flux.from(publisher)
      case HttpEntity.Chunked(_, chunks) =>
        val publisher = chunks
          .map(chunk => dataBufferFactory.wrap(chunk.data.toByteBuffer))
          .runWith(Sink.asPublisher(fanout = false))
        Flux.from(publisher)
      case _ =>
        Flux.empty()
    }
  }

  override def getId(): String = pekkoRequest.uri.toString()

  override def getRemoteAddress(): InetSocketAddress = {
    // Pekko HTTP doesn't expose remote address in HttpRequest directly
    new InetSocketAddress("0.0.0.0", 0)
  }

  override def getCookies(): org.springframework.util.MultiValueMap[String, org.springframework.http.HttpCookie] = {
    new org.springframework.util.LinkedMultiValueMap[String, org.springframework.http.HttpCookie]()
  }

  override def getSslInfo(): org.springframework.http.server.reactive.SslInfo = null

  override def mutate(): ServerHttpRequest.Builder = throw new UnsupportedOperationException("mutate not supported")

  override def getAttributes(): java.util.Map[String, Object] = {
    new java.util.HashMap[String, Object]()
  }

  override def getPath(): org.springframework.http.server.RequestPath = {
    org.springframework.http.server.RequestPath.parse(pekkoRequest.uri.path.toString(), null)
  }

  override def getQueryParams(): org.springframework.util.MultiValueMap[String, String] = {
    val queryParams = new org.springframework.util.LinkedMultiValueMap[String, String]()
    pekkoRequest.uri.query().foreach { case (key, value) =>
      queryParams.add(key, value)
    }
    queryParams
  }
}

