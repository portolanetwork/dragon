package app.dragon.turnstile.controller

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.StdIn

object AkkaTurnstile extends App {
  init()

  private def init(): Unit = {
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](Guardian(), "turnstile", ApplicationConfig.rootConfig)

    PekkoManagement(system).start()
    ClusterBootstrap(system).start()

    println(">>> Press ENTER to exit <<<")
    StdIn.readLine()

    system.terminate()
  }
}
