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

package app.dragon.turnstile.db

import com.github.tminglei.slickpg.*
import io.circe.{Json, parser}
import slick.basic.Capability
import slick.jdbc.JdbcCapabilities

/**
 * Custom Postgres profile with JSON support via slick-pg.
 *
 * This profile extends the standard PostgresProfile with:
 * - JSONB support for native PostgreSQL JSON columns
 * - Circe JSON integration for JSON serialization/deserialization
 * - Array support
 *
 * Usage:
 * {{{
 * import app.dragon.turnstile.db.TurnstilePostgresProfile.api._
 *
 * class MyTable(tag: Tag) extends Table[MyRow](tag, "my_table") {
 *   def jsonColumn = column[Json]("json_data")
 * }
 * }}}
 */
trait TurnstilePostgresProfile extends ExPostgresProfile
    with PgArraySupport
    with PgCirceJsonSupport {

  // Add JSON array support
  def pgjson = "jsonb"

  // Override the API to include our custom types
  override val api = TurnstileAPI

  object TurnstileAPI extends ExtPostgresAPI
      with ArrayImplicits
      with CirceImplicits

  // Configure capabilities
  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities + JdbcCapabilities.insertOrUpdate
}

/**
 * Singleton instance of the custom Postgres profile.
 * Use this for importing the API and creating database instances.
 */
object TurnstilePostgresProfile extends TurnstilePostgresProfile
