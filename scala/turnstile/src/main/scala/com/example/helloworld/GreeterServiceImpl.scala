package com.example.helloworld

//#import
import scala.concurrent.Future

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.typed.ActorSystem
import pekko.stream.scaladsl.BroadcastHub
import pekko.stream.scaladsl.Keep
import pekko.stream.scaladsl.MergeHub
import pekko.stream.scaladsl.Sink
import pekko.stream.scaladsl.Source

//#import

//#service-request-reply
//#service-stream
class GreeterServiceImpl(system: ActorSystem[_]) extends GreeterService {
  private implicit val sys: ActorSystem[_] = system

  //#service-request-reply
  val (inboundHub: Sink[HelloRequest, NotUsed], outboundHub: Source[HelloReply, NotUsed]) =
    MergeHub.source[HelloRequest]
    .map(request => HelloReply(s"Hello, ${request.name}"))
      .toMat(BroadcastHub.sink[HelloReply])(Keep.both)
      .run()
  //#service-request-reply

  override def sayHello(request: HelloRequest): Future[HelloReply] = {
    Future.successful(HelloReply(s"Hello, ${request.name}"))
  }

  //#service-request-reply
  override def sayHelloToAll(in: Source[HelloRequest, NotUsed]): Source[HelloReply, NotUsed] = {
    in.runWith(inboundHub)
    outboundHub
  }
  //#service-request-reply
}
//#service-stream
//#service-request-reply
