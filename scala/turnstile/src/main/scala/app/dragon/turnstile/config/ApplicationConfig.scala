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

package app.dragon.turnstile.config

import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Application configuration management.
 *
 * This object provides centralized access to application configuration loaded from
 * Typesafe Config (HOCON) files. It supports environment-specific configurations
 * selected via the DEPLOYMENT_NAME environment variable.
 *
 * Configuration Strategy:
 * - Default: application.conf (development/local)
 * - CI: ci.conf (continuous integration)
 * - Staging: staging.conf (pre-production)
 * - Production: production.conf (production)
 *
 * Environment Selection:
 * {{{
 * export DEPLOYMENT_NAME=production
 * sbt run  # loads production.conf
 * }}}
 *
 * Configuration Sections:
 * - turnstile.grpc: gRPC server settings (host, port)
 * - turnstile.mcp-streaming: MCP gateway settings (enabled, host, port, server-name, etc.)
 * - turnstile.db.pgsql: Database connection settings (url, user, password)
 * - pekko.*: Actor system, cluster, remoting, management
 *
 * All configuration files should follow the structure defined in application.conf.
 * See src/main/resources/application.conf for the complete configuration schema.
 *
 * Usage:
 * {{{
 * val grpcHost = ApplicationConfig.grpcConfig.getString("host")
 * val grpcPort = ApplicationConfig.grpcConfig.getInt("port")
 * }}}
 */
object ApplicationConfig {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  /** Address configuration (host and port) */
  final case class Address(host: String, port: Int)

  /** Application-level configuration */
  final case class AppConfig(baseUrl: String, tlsEnabled: Boolean, tlsSkipVerify: Boolean)

  logger.info("Loading application configuration...")

  /** Deployment environment name from environment variable */
  val deploymentName: String = sys.env.get("DEPLOYMENT_NAME").getOrElse("default")

  logger.info(s"Deployment name: $deploymentName")

  /**
   * Select configuration file based on deployment environment.
   *
   * Maps DEPLOYMENT_NAME to corresponding .conf file:
   * - ci → ci.conf
   * - staging → staging.conf
   * - production → production.conf
   * - default (or any other value) → application.conf
   */
  val configFile: String = deploymentName match {
    case "default" => "application.conf"
    case deployment => deployment + ".conf"
  }

  /** Root configuration object loaded from the selected file */
  val rootConfig: Config = ConfigFactory.load(configFile)

  /** Authentication configuration */
  val auth: Config = rootConfig.getConfig("turnstile.auth")

  /** Server authentication enabled flag */
  val serverAuthEnabled: Boolean = auth.getBoolean("server.enabled")

  /** gRPC server configuration (host, port) */
  val grpcConfig: Config = rootConfig.getConfig("turnstile.grpc")

  /** gRPC-Web server configuration (host, port, enabled) */
  val grpcWebConfig: Config = rootConfig.getConfig("turnstile.grpc-web")

  /** MCP streaming HTTP server configuration */
  val mcpStreaming: Config = rootConfig.getConfig("turnstile.mcp.streaming-http")

  /** Database configuration for Slick and Flyway */
  val db: Config = rootConfig.getConfig("turnstile.db.pgsql")
  
  /** Event log configuration */
  val eventLog: Config = rootConfig.getConfig("turnstile.event-log")

  logger.info(s"Configuration loaded from: $configFile")
}
