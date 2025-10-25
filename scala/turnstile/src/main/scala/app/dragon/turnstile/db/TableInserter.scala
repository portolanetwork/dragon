package app.dragon.turnstile.db

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.JdbcBackend.Database
import app.dragon.turnstile.db.TurnstilePostgresProfile.api._
import app.dragon.turnstile.db.Tables
import app.dragon.turnstile.db.McpServerRow

object TableInserter {
  /**
   * Inserts a McpServerRow and returns the inserted row with the generated ID.
   * @param row The McpServerRow to insert
   * @param db The database instance
   * @param ec ExecutionContext
   * @return Future[McpServerRow] with the inserted row
   */
  def insertMcpServer(row: McpServerRow)(implicit db: Database, ec: ExecutionContext): Future[McpServerRow] = {
    val insertAction = (Tables.mcpServers returning Tables.mcpServers.map(_.id) into ((row, id) => row.copy(id = id))) += row
    db.run(insertAction)
  }

  /**
   * Lists all MCP servers for a given user.
   * @param userId The user identifier
   * @param db The database instance
   * @param ec ExecutionContext
   * @return Future[Seq[McpServerRow]] with all servers for the user
   */
  def listMcpServers(userId: String)(implicit db: Database, ec: ExecutionContext): Future[Seq[McpServerRow]] = {
    val query = Tables.mcpServers.filter(_.userId === userId).result
    db.run(query)
  }
}

