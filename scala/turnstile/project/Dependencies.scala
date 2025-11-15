/*
 * Copyright 2025 Sami Malik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: Sami Malik (sami.malik [at] portolanetwork.io)
 */

import sbt.*

object Dependencies {
  val pekkoVersion = "1.2.1"
  val pekkoHttpVersion = "1.2.0"
  val pekkoGrpcVersion = "1.1.1"
  val akkaManagementVersion = "1.1.1"
  val json4sJacksonVersion = "4.0.7"
  val circeVersion = "0.14.6"
  val logbackVersion = "1.5.15"
  val scalaLoggingVersion = "3.9.5"
  val scalaMockVersion = "6.1.1"
  val scalaTestVersion = "3.2.19"
  val sslConfigCoreVersion = "0.6.1"
  val bouncyCastleVersion = "1.70"
  val apacheCommonsIoVersion = "2.18.0"
  val jwtScalaVersion = "10.0.1"
  val auth0JwksRsaVersion = "0.22.1"
  val protobufJavaUtilVersion = "3.25.6"
  val mcpSdkVersion = "0.14.1"
  val slickVersion = "3.5.2"
  val slickPgVersion = "0.22.2"
  val postgresqlVersion = "42.7.4"
  val hikariCPVersion = "6.2.1"
  val flywayVersion = "11.1.0"
  val springVersion = "6.2.1"

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
    // Circe for JSON parsing/encoding used by ClientAuthService
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    // sttp client for HTTP calls used by ClientAuthService
    "com.softwaremill.sttp.client4" %% "core" % "4.0.13",
    "com.softwaremill.sttp.client4" %% "circe" % "4.0.13",
    "com.softwaremill.sttp.client4" %% "okhttp-backend" % "4.0.13",
    // (using io.circe.generic.auto._ instead of circe-generic-extras)
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "com.github.jwt-scala" %% "jwt-play-json" % jwtScalaVersion,
    "com.github.jwt-scala" %% "jwt-core" % jwtScalaVersion,
    "com.github.jwt-scala" %% "jwt-play" % jwtScalaVersion,
    "com.auth0" % "jwks-rsa" % auth0JwksRsaVersion,
    "com.google.protobuf" % "protobuf-java-util" % protobufJavaUtilVersion,
    // MCP (Model Context Protocol)
    "io.modelcontextprotocol.sdk" % "mcp" % mcpSdkVersion,
    "io.modelcontextprotocol.sdk" % "mcp-core" % mcpSdkVersion,
    "io.modelcontextprotocol.sdk" % "mcp-spring-webflux" % mcpSdkVersion,
    // Database - Slick ORM with PostgreSQL
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.github.tminglei" %% "slick-pg" % slickPgVersion,
    "com.github.tminglei" %% "slick-pg_play-json" % slickPgVersion,
    "org.postgresql" % "postgresql" % postgresqlVersion,
    "com.zaxxer" % "HikariCP" % hikariCPVersion,
    "org.flywaydb" % "flyway-core" % flywayVersion,
    "org.flywaydb" % "flyway-database-postgresql" % flywayVersion,
    // Test
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    "org.scalamock" %% "scalamock" % scalaMockVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "com.h2database" % "h2" % "2.3.232" % Test,  // H2 for testing

    // Ensure Spring Context is present for classes such as org.springframework.context.i18n.LocaleContext
    "org.springframework" % "spring-context" % springVersion,
  )

  val dependencyOverrides = Seq()
}