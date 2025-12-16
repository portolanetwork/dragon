import app.dragon.turnstile.{BuildInfo, DockerConfig, TurnstileBuild}

ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "app.dragon.turnstile"
ThisBuild / organizationName := "Turnstile"
ThisBuild / dynverSeparator := "-"

pekkoGrpcGeneratedSources := Seq(PekkoGrpc.Server, PekkoGrpc.Client)
pekkoGrpcCodeGeneratorSettings += "server_power_apis"

Compile / PB.protoSources := Seq(
  sourceDirectory.value / ".." / ".." / ".." / "resources" / "proto",
  sourceDirectory.value / "main" / "protobuf"
)

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

// ================================================================================================
// Docker Configuration
// ================================================================================================

Docker / packageName := packageName.value
Docker / version := version.value
Docker / defaultLinuxInstallLocation := DockerConfig.installLocation
Docker / dockerGroupLayers := PartialFunction.empty
Docker / mappings ++= DockerConfig.resourceMappings

dockerRepository := DockerConfig.registryUrl
dockerUsername := DockerConfig.registryUsername
dockerUpdateLatest := true
dockerCommands := DockerConfig.dockerCommands
