package app.dragon.turnstile.controller

import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.{Logger, LoggerFactory}

object ApplicationConfig {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  final case class Address(host: String, port: Int)
  final case class AppConfig(baseUrl: String, tlsEnabled: Boolean, tlsSkipVerify: Boolean)

  logger.info("Loading application configuration...")

  val deploymentName: String = sys.env.get("DEPLOYMENT_NAME").getOrElse("default")

  logger.info(s"Deployment name: $deploymentName")

  // Construct the configuration file name based on the environment
  val configFile: String = deploymentName match {
    case "ci" => "ci.conf"
    case "staging" => "staging.conf"
    case "production" => "production.conf"
    case _ => "application.conf" // Fallback to default configuration
  }

  val rootConfig: Config = ConfigFactory.load(configFile)
  val grpcConfig: Config = rootConfig.getConfig("turnstile.grpc")

  logger.info(s"Configuration loaded from: $configFile")
}
