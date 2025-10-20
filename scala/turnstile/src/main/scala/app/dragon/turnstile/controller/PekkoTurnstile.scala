package app.dragon.turnstile.controller

import app.dragon.turnstile.config.ApplicationConfig
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.io.StdIn

object PekkoTurnstile extends App {
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
