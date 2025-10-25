package app.dragon.turnstile.db

import app.dragon.turnstile.db.TurnstilePostgresProfile.api.{*, given}

import java.sql.Timestamp

/**
 * Slick table definitions for MCP server persistence.
 *
 * Database Schema:
 * - mcp_servers: Stores user-registered MCP servers
 *   - id: Auto-incrementing primary key
 *   - user_id: User identifier
 *   - uuid: Server UUID (unique per user)
 *   - name: Server name
 *   - url: Server URL
 *   - client_id: OAuth client ID (optional)
 *   - client_secret: OAuth client secret (optional)
 *   - created_at: Timestamp of creation
 *   - updated_at: Timestamp of last update
 *
 * Indexes:
 * - idx_mcp_servers_user_id: Fast lookup by user
 * - idx_mcp_servers_user_uuid: Unique constraint on (user_id, uuid)
 */

/**
 * MCP server table row representation
 *
 * @param id Auto-incrementing primary key
 * @param userId User identifier
 * @param uuid Server UUID (unique per user)
 * @param name Server name
 * @param url Server URL
 * @param clientId OAuth client ID (optional)
 * @param clientSecret OAuth client secret (optional)
 * @param createdAt Creation timestamp
 * @param updatedAt Last update timestamp
 */
case class McpServerRow(
  id: Long = 0L,
  userId: String,
  uuid: String,
  name: String,
  url: String,
  clientId: Option[String] = None,
  clientSecret: Option[String] = None,
  createdAt: Timestamp = new Timestamp(System.currentTimeMillis()),
  updatedAt: Timestamp = new Timestamp(System.currentTimeMillis())
)

/**
 * Slick table definition for mcp_servers
 */
class McpServersTable(tag: Tag) extends Table[McpServerRow](tag, "mcp_servers") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def userId = column[String]("user_id")
  def uuid = column[String]("uuid")
  def name = column[String]("name")
  def url = column[String]("url")
  def clientId = column[Option[String]]("client_id")
  def clientSecret = column[Option[String]]("client_secret")
  def createdAt = column[Timestamp]("created_at")
  def updatedAt = column[Timestamp]("updated_at")

  // Index on user_id for fast lookup
  def idxUserId = index("idx_mcp_servers_user_id", userId)

  // Unique index on (user_id, uuid)
  def idxUserUuid = index("idx_mcp_servers_user_uuid", (userId, uuid), unique = true)

  def * = (id, userId, uuid, name, url, clientId, clientSecret, createdAt, updatedAt).mapTo[McpServerRow]
}

/**
 * Table queries object for accessing tables
 */
object Tables {
  /**
   * MCP servers table query
   */
  val mcpServers = TableQuery[McpServersTable]
}
