package app.dragon.turnstile.db

import app.dragon.turnstile.db.TurnstilePostgresProfile.api.{*, given}

import java.sql.Timestamp

/**
 * Slick table definitions for MCP server persistence.
 *
 * Database Schema:
 * - mcp_server: Stores user-registered MCP servers
 *   - id: Auto-incrementing primary key
 *   - uuid: Server UUID (unique per tenant+user)
 *   - tenant: Tenant identifier for multi-tenancy
 *   - user_id: User identifier
 *   - name: Server name
 *   - url: Server URL
 *   - client_id: OAuth client ID (optional)
 *   - client_secret: OAuth client secret (optional)
 *   - refresh_token: OAuth refresh token (optional)
 *   - created_at: Timestamp of creation
 *   - updated_at: Timestamp of last update
 *
 * Indexes:
 * - idx_mcp_servers_tenant: Fast lookup by tenant
 * - idx_mcp_servers_tenant_user: Lookup by tenant and user
 * - idx_mcp_servers_tenant_user_uuid: Unique constraint on (tenant, user_id, uuid)
 */

/**
 * MCP server table row representation
 *
 * @param id Auto-incrementing primary key
 * @param uuid Server UUID (unique per tenant+user)
 * @param tenant Tenant identifier
 * @param userId User identifier
 * @param name Server name
 * @param url Server URL
 * @param clientId OAuth client ID (optional)
 * @param clientSecret OAuth client secret (optional)
 * @param refreshToken OAuth refresh token (optional)
 * @param createdAt Creation timestamp
 * @param updatedAt Last update timestamp
 */
case class McpServerRow(
  id: Long = 0L,
  uuid: String,
  tenant: String,
  userId: String,
  name: String,
  url: String,
  clientId: Option[String] = None,
  clientSecret: Option[String] = None,
  refreshToken: Option[String] = None,
  createdAt: Timestamp = new Timestamp(System.currentTimeMillis()),
  updatedAt: Timestamp = new Timestamp(System.currentTimeMillis())
)

/**
 * Slick table definition for mcp_server
 */
class McpServersTable(tag: Tag) extends Table[McpServerRow](tag, "mcp_server") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def uuid = column[String]("uuid")
  def tenant = column[String]("tenant")
  def userId = column[String]("user_id")
  def name = column[String]("name")
  def url = column[String]("url")
  def clientId = column[Option[String]]("client_id")
  def clientSecret = column[Option[String]]("client_secret")
  def refreshToken = column[Option[String]]("refresh_token")
  def createdAt = column[Timestamp]("created_at")
  def updatedAt = column[Timestamp]("updated_at")

  // Index on tenant for fast lookup
  def idxTenant = index("idx_mcp_servers_tenant", tenant)

  // Index on (tenant, user_id)
  def idxTenantUser = index("idx_mcp_servers_tenant_user", (tenant, userId))

  // Unique index on (tenant, user_id, uuid)
  def idxTenantUserUuid = index("idx_mcp_servers_tenant_user_uuid", (tenant, userId, uuid), unique = true)

  def * = (id, uuid, tenant, userId, name, url, clientId, clientSecret, refreshToken, createdAt, updatedAt).mapTo[McpServerRow]
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
