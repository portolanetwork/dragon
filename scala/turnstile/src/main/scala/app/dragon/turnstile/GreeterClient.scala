package app.dragon.turnstile

//#import
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import org.apache.pekko
import pekko.Done
import pekko.NotUsed
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.grpc.GrpcClientSettings
import pekko.stream.scaladsl.Source
import com.example.helloworld.{GreeterServiceClient, HelloRequest, HelloReply}

//#import

//#client-request-reply
object GreeterClient {

  def main(args: Array[String]): Unit = {
    // Use minimal config without clustering
    val config = com.typesafe.config.ConfigFactory.parseString(
      """
      pekko.http.server.preview.enable-http2 = on
      pekko.grpc.client."helloworld.GreeterService" {
        host = 127.0.0.1
        port = 8080
        use-tls = false
      }
      """
    ).withFallback(com.typesafe.config.ConfigFactory.defaultReference())

    implicit val sys: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty[Nothing], "GreeterClient", config)
    implicit val ec: ExecutionContext = sys.executionContext

    val client = GreeterServiceClient(GrpcClientSettings.fromConfig("helloworld.GreeterService"))

    val names =
      if (args.isEmpty) List("Alice", "Bob")
      else args.toList

    names.foreach(singleRequestReply)

    //#client-request-reply
    if (args.nonEmpty)
      names.foreach(streamingBroadcast)
    //#client-request-reply

    def singleRequestReply(name: String): Unit = {
      println(s"Performing request: $name")
      val reply = client.sayHello(HelloRequest(name))
      reply.onComplete {
        case Success(msg) =>
          println(msg)
        case Failure(e) =>
          println(s"Error: $e")
      }
    }

    //#client-request-reply
    //#client-stream
    def streamingBroadcast(name: String): Unit = {
      println(s"Performing streaming requests: $name")

      val requestStream: Source[HelloRequest, NotUsed] =
        Source
          .tick(1.second, 1.second, "tick")
          .zipWithIndex
          .map { case (_, i) => i }
          .map(i => HelloRequest(s"$name-$i"))
          .mapMaterializedValue(_ => NotUsed)

      val responseStream: Source[HelloReply, NotUsed] = client.sayHelloToAll(requestStream)
      val done: Future[Done] =
        responseStream.runForeach(reply => println(s"$name got streaming reply: ${reply.message}"))

      done.onComplete {
        case Success(_) =>
          println("streamingBroadcast done")
        case Failure(e) =>
          println(s"Error streamingBroadcast: $e")
      }
    }
    //#client-stream
    //#client-request-reply

  }

}
//#client-request-reply
