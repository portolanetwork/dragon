import sbt.*

object Dependencies {
  val pekkoVersion = "1.2.1"
  val pekkoHttpVersion = "1.2.0"
  val pekkoGrpcVersion = "1.1.1"
  val akkaManagementVersion = "1.1.1"
  val json4sJacksonVersion = "4.0.7"
  val logbackVersion = "1.5.15"
  val scalaLoggingVersion = "3.9.5"
  val scalaMockVersion = "6.1.1"
  val scalaTestVersion = "3.2.19"
  val sslConfigCoreVersion = "0.6.1"
  val bouncyCastleVersion = "1.70"
  val apacheCommonsIoVersion = "2.18.0"
  val jwtScalaVersion = "10.0.1"
  val protobufJavaUtilVersion = "3.25.6"

  val dependencies = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-tools" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-distributed-data" % pekkoVersion,
    "org.apache.pekko" %% "pekko-multi-node-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-persistence" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-tck" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-query" % pekkoVersion,
    "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoVersion,
    "org.apache.pekko" %% "pekko-remote" % pekkoVersion,
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion,
    "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-coordination" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-metrics" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-sharding" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-grpc-runtime" % pekkoGrpcVersion,
    "org.apache.pekko" %% "pekko-pki" % pekkoVersion,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
    "com.typesafe" %% "ssl-config-core" % sslConfigCoreVersion,
    "org.bouncycastle" % "bcprov-jdk15on" % bouncyCastleVersion,
    "org.bouncycastle" % "bcpkix-jdk15on" % bouncyCastleVersion,
    "org.bouncycastle" % "bctls-jdk15on" % bouncyCastleVersion,
    "commons-io" % "commons-io" % apacheCommonsIoVersion,
    "org.apache.pekko" %% "pekko-management" % akkaManagementVersion,
    "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % akkaManagementVersion,
    "org.apache.pekko" %% "pekko-discovery-kubernetes-api" % akkaManagementVersion,
    "org.json4s" %% "json4s-jackson" % json4sJacksonVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "com.github.jwt-scala" %% "jwt-play-json" % jwtScalaVersion,
    "com.github.jwt-scala" %% "jwt-core" % jwtScalaVersion,
    "com.github.jwt-scala" %% "jwt-play" % jwtScalaVersion,
    "com.google.protobuf" % "protobuf-java-util" % protobufJavaUtilVersion,
    // Test
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    "org.scalamock" %% "scalamock" % scalaMockVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  )

  val dependencyOverrides = Seq()
}