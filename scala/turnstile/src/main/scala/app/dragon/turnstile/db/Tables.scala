package app.dragon.turnstile.db

import app.dragon.turnstile.db.TurnstilePostgresProfile.api.{*, given}
import app.dragon.turnstile.db.TurnstilePostgresProfile.TurnstileAPI.playJsonTypeMapper
import play.api.libs.json.JsValue

import java.sql.Timestamp

/**
 * Slick table definitions for tools persistence.
 *
 * Database Schema:
 * - user_tools: Stores user-specific custom tools
 *   - id: Auto-incrementing primary key
 *   - user_id: User identifier
 *   - tool_name: Unique tool name (per user)
 *   - description: Tool description
 *   - schema_json: JSON schema for tool parameters
 *   - created_at: Timestamp of creation
 *   - updated_at: Timestamp of last update
 *
 * Indexes:
 * - idx_user_tools_user_id: Fast lookup by user
 * - idx_user_tools_user_tool: Unique constraint on (user_id, tool_name)
 */

/**
 * User tools table row representation
 *
 * @param id Auto-incrementing primary key
 * @param userId User identifier
 * @param toolName Tool name (unique per user)
 * @param description Tool description
 * @param schemaJson JSON schema as Play JSON JsValue (stored as JSONB in PostgreSQL)
 * @param createdAt Creation timestamp
 * @param updatedAt Last update timestamp
 */
case class UserToolRow(
  id: Long = 0L,
  userId: String,
  toolName: String,
  description: String,
  schemaJson: JsValue,
  createdAt: Timestamp = new Timestamp(System.currentTimeMillis()),
  updatedAt: Timestamp = new Timestamp(System.currentTimeMillis())
)

/**
 * Slick table definition for user_tools using JSONB for schema storage
 */
class UserToolsTable(tag: Tag) extends Table[UserToolRow](tag, "user_tools") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def userId = column[String]("user_id")
  def toolName = column[String]("tool_name")
  def description = column[String]("description")
  def schemaJson = column[JsValue]("schema_json") // JSONB column
  def createdAt = column[Timestamp]("created_at")
  def updatedAt = column[Timestamp]("updated_at")

  // Unique index on (user_id, tool_name)
  def idx = index("idx_user_tools_user_tool", (userId, toolName), unique = true)

  def * = (id, userId, toolName, description, schemaJson, createdAt, updatedAt).mapTo[UserToolRow]
}

/**
 * Table queries object for accessing tables
 */
object Tables {
  /**
   * User tools table query
   */
  val userTools = TableQuery[UserToolsTable]
}
