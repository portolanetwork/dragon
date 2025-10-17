import app.dragon.turnstile.{BuildInfo, TurnstileBuild}
import com.typesafe.sbt.packager.docker.*

ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "app.dragon.turnstile"
ThisBuild / organizationName := "Turnstile"
ThisBuild / dynverSeparator := "-"

pekkoGrpcGeneratedSources := Seq(PekkoGrpc.Server, PekkoGrpc.Client)
pekkoGrpcCodeGeneratorSettings += "server_power_apis"

lazy val root = (project in file("."))
  .enablePlugins(DockerPlugin, UniversalPlugin)
  .enablePlugins(JavaAgent, JavaAppPackaging)
  .enablePlugins(PekkoGrpcPlugin)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "turnstile",
    resolvers ++= ProjectResolvers.resolvers,
    libraryDependencies ++= Dependencies.dependencies,
    dependencyOverrides ++= Dependencies.dependencyOverrides,
    javaAgents ++= JavaAgents.javaAgents,
    scalacOptions ++= TurnstileBuild.DefaultScalacOptions,
    buildInfoKeys ++= BuildInfo.customBuildInfoKeys ++ Seq[BuildInfoKey](
      BuildInfoKey.map(name) { case (key, value) => "projectName" -> value },
      version
    ),
    buildInfoPackage := BuildInfo.buildInfoPackage,
    buildInfoOptions := Seq(
      BuildInfoOption.BuildTime,
      BuildInfoOption.ToJson,
    ),
  )
