package app.dragon.turnstile.db

import com.typesafe.config.Config
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

/**
 * Database migration management using Flyway.
 *
 * Handles:
 * - Running pending migrations
 * - Database schema versioning
 * - Migration validation
 *
 * Migrations are located in: src/main/resources/db/migration/
 * Files follow Flyway naming convention: V<version>__<description>.sql
 *
 * Example: V1__create_mcp_servers_table.sql
 */
object DatabaseMigration {
  private val logger: Logger = LoggerFactory.getLogger(DatabaseMigration.getClass)

  /**
   * Run database migrations
   *
   * @param config Application configuration containing database settings
   * @return Success with migration result, or Failure with exception
   */
  def migrate(config: Config): Try[MigrateResult] = {
    logger.info("Starting database migrations...")

    Try {
      val dbConfig = config.getConfig("turnstile.database.db")
      val url = dbConfig.getString("url")
      val user = dbConfig.getString("user")
      val password = dbConfig.getString("password")

      logger.info(s"Connecting to database: $url")

      val flyway = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true) // Auto-baseline for existing databases
        .baselineVersion("0")
        .load()

      // Run migrations
      val result = flyway.migrate()

      if (result.migrationsExecuted > 0) {
        logger.info(s"Successfully executed ${result.migrationsExecuted} migrations")
        logger.info(s"Target schema version: ${result.targetSchemaVersion}")
      } else {
        logger.info("Database schema is up to date, no migrations needed")
      }

      result
    } match {
      case Success(result) =>
        logger.info("Database migrations completed successfully")
        Success(result)

      case Failure(ex) =>
        logger.error("Database migration failed", ex)
        Failure(ex)
    }
  }

  /**
   * Validate pending migrations without running them
   */
  def validate(config: Config): Try[Unit] = {
    logger.info("Validating database migrations...")

    Try {
      val dbConfig = config.getConfig("turnstile.database.db")
      val url = dbConfig.getString("url")
      val user = dbConfig.getString("user")
      val password = dbConfig.getString("password")

      val flyway = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()

      flyway.validate()
      logger.info("Database migrations validation passed")
    }.recoverWith { case ex =>
      logger.error("Database migrations validation failed", ex)
      Failure(ex)
    }
  }

  /**
   * Get information about migration status
   */
  def info(config: Config): Try[String] = {
    Try {
      val dbConfig = config.getConfig("turnstile.database.db")
      val url = dbConfig.getString("url")
      val user = dbConfig.getString("user")
      val password = dbConfig.getString("password")

      val flyway = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()

      val info = flyway.info()
      val current = info.current()
      val pending = info.pending()

      val status = new StringBuilder
      status.append(s"Current version: ${if (current != null) current.getVersion else "none"}\n")
      status.append(s"Pending migrations: ${pending.length}\n")

      if (pending.nonEmpty) {
        status.append("\nPending migrations:\n")
        pending.foreach { migration =>
          status.append(s"  - ${migration.getVersion}: ${migration.getDescription}\n")
        }
      }

      status.toString()
    }.recoverWith { case ex =>
      logger.error("Failed to get migration info", ex)
      Failure(ex)
    }
  }

  /**
   * Clean database (WARNING: Drops all objects in configured schemas)
   * Only use for testing/development!
   */
  def clean(config: Config): Try[Unit] = {
    logger.warn("CLEANING DATABASE - This will drop all objects!")

    Try {
      val dbConfig = config.getConfig("turnstile.database.db")
      val url = dbConfig.getString("url")
      val user = dbConfig.getString("user")
      val password = dbConfig.getString("password")

      val flyway = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load()

      flyway.clean()
      logger.info("Database cleaned successfully")
    }.recoverWith { case ex =>
      logger.error("Database clean failed", ex)
      Failure(ex)
    }
  }
}
