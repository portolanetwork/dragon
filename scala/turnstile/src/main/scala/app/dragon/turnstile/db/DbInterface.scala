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

import app.dragon.turnstile.db.Tables
import app.dragon.turnstile.db.TurnstilePostgresProfile.api.*
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * Lightweight DB error ADT for wrapping database failures.
 */
sealed trait DbError {
  def message: String
}
final case class DbFailure(message: String, cause: Option[Throwable] = None) extends DbError
case object DbNotFound extends DbError { val message: String = "not found" }
case object DbAlreadyExists extends DbError { val message: String = "already exists" }

object DbInterface {
  // Helper that inspects a throwable (and its cause chain) to determine if it's a unique-constraint error
  private def mapDbError(t: Throwable): DbError = {
    // iterate through causes to find SQLExceptions with SQLState 23505 (unique violation) or messages containing "duplicate"
    val found = Iterator.iterate(Option(t))(_.flatMap(th => Option(th.getCause))).takeWhile(_.isDefined).collectFirst {
      case Some(ps: java.sql.SQLException) if Option(ps.getSQLState).contains("23505") => DbAlreadyExists
      case Some(ps: java.sql.SQLException) if ps.getMessage != null && ps.getMessage.toLowerCase.contains("duplicate") => DbAlreadyExists
      case Some(th) if th.getMessage != null && th.getMessage.toLowerCase.contains("duplicate") => DbAlreadyExists
    }
    found.getOrElse(DbFailure(t.getMessage, Some(t)))
  }

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
    val insertAction =
      (Tables.mcpServers
        returning Tables.mcpServers.map(_.id)
        into ((row, id) => row.copy(id = id))) += row
    db.run(insertAction)
      .map(Right(_): Either[DbError, McpServerRow])
      .recover {
        case NonFatal(t) => Left(mapDbError(t))
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
    val query =
      Tables.mcpServers
        .filter(s => s.tenant === tenant && s.userId === userId).result
    db.run(query).map(Right(_): Either[DbError, Seq[McpServerRow]]).recover {
      case NonFatal(t) => Left(mapDbError(t))
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
    uuid: UUID
  )(
    implicit db: Database, ec: ExecutionContext
  ): Future[Either[DbError, Int]] = {
    val deleteAction = Tables.mcpServers
      .filter(_.uuid === uuid).delete
    db.run(deleteAction).map(Right(_): Either[DbError, Int]).recover {
      case NonFatal(t) => Left(mapDbError(t))
    }
  }
}
