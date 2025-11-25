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

package app.dragon.turnstile

import com.typesafe.sbt.packager.docker.*
import sbt.*

object DockerConfig {

  // Base image configuration
  val baseImageName = "docker.io/library/eclipse-temurin:17-jre"

  // Registry settings
  val registryUrl = Some("registry.digitalocean.com")
  val registryUsername = Some("portola")

  // Installation paths
  val installLocation = "/opt/turnstile"

  // Resource file mappings
  val resourceMappings = Seq(
    file("src/main/resources/jmx-prometheus.yaml") -> s"$installLocation/conf/jmx-prometheus.yaml",
    file("src/main/resources/logback.xml")         -> s"$installLocation/conf/logback.xml",
  )

  // Exposed ports
  val exposedPorts = Seq(
    "8080",  // gRPC server
    "8081",  // grpc-web server
    "8082",  // MCP Server - Streaming endpoint
    "8558",  // Pekko Management HTTP
    "9404",  // JMX Prometheus metrics
    "25520"  // Pekko remoting
  )

  // Multi-stage Dockerfile commands
  def dockerCommands: Seq[CmdLike] = {
    val commands = Seq.newBuilder[CmdLike]

    // ============================================================================================
    // Stage 0: Application layer
    // ============================================================================================
    commands += Cmd("FROM", s"$baseImageName AS stage0")
    commands += Cmd("LABEL", "snp-multi-stage=intermediate")
    commands += Cmd("LABEL", "snp-multi-stage-id=stage0")
    commands += Cmd("WORKDIR", installLocation)
    commands += Cmd("COPY", "opt", "/opt")
    commands += Cmd("USER", "root")
    commands ++= Seq(
      ExecCmd("RUN", "chmod", "-R", "u=rX,g=rX", installLocation),
      ExecCmd("RUN", "chmod", "u+x,g+x", s"$installLocation/bin/turnstile")
    )

    // ============================================================================================
    // Final stage: Runtime image
    // ============================================================================================
    commands += Cmd("FROM", baseImageName)
    commands += Cmd("WORKDIR", installLocation)
    commands += Cmd("USER", "root")

    // Copy application from build stage
    commands += Cmd("COPY", s"--from=stage0 --chown=daemon:root", installLocation, installLocation)

    // Create log directory
    commands += ExecCmd("RUN", "mkdir", "-p", "/var/log/turnstile")
    commands += ExecCmd("RUN", "chown", "-R", "daemon:root", "/var/log/turnstile")

    // Volume for logs
    commands += Cmd("VOLUME", "/var/log/turnstile")

    // Expose ports
    commands += Cmd("EXPOSE", exposedPorts.mkString(" "))

    // Runtime user
    commands += Cmd("USER", "daemon")

    // Application entrypoint
    commands += ExecCmd("ENTRYPOINT", s"$installLocation/bin/turnstile")

    commands.result()
  }
}
