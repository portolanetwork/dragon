import sbt.*

object ProjectResolvers {
  val publicAkkaRepo = "Akka library repository".at("https://repo.org.apache.pekko.io/maven")

  val resolvers = Seq(
    publicAkkaRepo
  )
}
