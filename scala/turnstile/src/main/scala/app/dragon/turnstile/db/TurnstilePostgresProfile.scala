package app.dragon.turnstile.db

import com.github.tminglei.slickpg.*
import play.api.libs.json.{JsValue, Json}
import slick.basic.Capability
import slick.jdbc.JdbcCapabilities

/**
 * Custom Postgres profile with JSON support via slick-pg.
 *
 * This profile extends the standard PostgresProfile with:
 * - JSONB support for native PostgreSQL JSON columns
 * - Play JSON integration for JSON serialization/deserialization
 * - Array support
 *
 * Usage:
 * {{{
 * import app.dragon.turnstile.db.TurnstilePostgresProfile.api._
 *
 * class MyTable(tag: Tag) extends Table[MyRow](tag, "my_table") {
 *   def jsonColumn = column[JsValue]("json_data")
 * }
 * }}}
 */
trait TurnstilePostgresProfile extends ExPostgresProfile
    with PgArraySupport
    with PgPlayJsonSupport {

  // Add JSON array support
  def pgjson = "jsonb"

  // Override the API to include our custom types
  override val api = TurnstileAPI

  object TurnstileAPI extends ExtPostgresAPI
      with ArrayImplicits
      with PlayJsonImplicits

  // Configure capabilities
  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities + JdbcCapabilities.insertOrUpdate
}

/**
 * Singleton instance of the custom Postgres profile.
 * Use this for importing the API and creating database instances.
 */
object TurnstilePostgresProfile extends TurnstilePostgresProfile
