package app.dragon.turnstile.db

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import slick.jdbc.JdbcBackend.Database
import app.dragon.turnstile.db.TurnstilePostgresProfile.api._
import app.dragon.turnstile.db.Tables
import app.dragon.turnstile.db.McpServerRow

/**
 * Lightweight DB error ADT for wrapping database failures.
 */
sealed trait DbError {
  def message: String
}
final case class DbFailure(message: String, cause: Option[Throwable] = None) extends DbError
case object DbNotFound extends DbError {
  val message: String = "not found"
}

object DbInterface {
  /**
   * Inserts a McpServerRow and returns the inserted row with the generated ID.
   * @param row The McpServerRow to insert
   * @param db The database instance
   * @param ec ExecutionContext
   * @return `Future[Either[DbError, McpServerRow]]` with the inserted row or DbError
   */
  def insertMcpServer(
    row: McpServerRow
  )(
    implicit db: Database, ec: ExecutionContext
  ): Future[Either[DbError, McpServerRow]] = {
    val insertAction = (Tables.mcpServers returning Tables.mcpServers.map(_.id) into ((row, id) => row.copy(id = id))) += row
    db.run(insertAction).map(Right(_): Either[DbError, McpServerRow]).recover {
      case NonFatal(t) => Left(DbFailure(t.getMessage, Some(t)))
    }
  }

  /**
   * Lists all MCP servers for a given tenant and user.
   * @param tenant The tenant identifier
   * @param userId The user identifier
   * @param db The database instance
   * @param ec ExecutionContext
   * @return `Future[Either[DbError, Seq[McpServerRow]]]` with all servers for the tenant and user or DbError
   */
  def listMcpServers(
    tenant: String,
    userId: String
  )(
    implicit db: Database, ec: ExecutionContext
  ): Future[Either[DbError, Seq[McpServerRow]]] = {
    val query = Tables.mcpServers.filter(s => s.tenant === tenant && s.userId === userId).result
    db.run(query).map(Right(_): Either[DbError, Seq[McpServerRow]]).recover {
      case NonFatal(t) => Left(DbFailure(t.getMessage, Some(t)))
    }
  }

  /**
   * Deletes an MCP server by UUID.
   * @param uuid The UUID of the server to delete
   * @param db The database instance
   * @param ec ExecutionContext
   * @return `Future[Either[DbError, Int]]` number of rows deleted or DbError
   */
  def deleteMcpServerByUuid(
    uuid: String
  )(
    implicit db: Database, ec: ExecutionContext
  ): Future[Either[DbError, Int]] = {
    val deleteAction = Tables.mcpServers.filter(_.uuid === uuid).delete
    db.run(deleteAction).map(Right(_): Either[DbError, Int]).recover {
      case NonFatal(t) => Left(DbFailure(t.getMessage, Some(t)))
    }
  }
}
