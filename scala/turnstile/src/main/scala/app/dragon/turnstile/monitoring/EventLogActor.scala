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

package app.dragon.turnstile.monitoring

import app.dragon.turnstile.db.TurnstilePostgresProfile.api.*
import app.dragon.turnstile.db.{EventLogRow, Tables}
import app.dragon.turnstile.serializer.TurnstileSerializable
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import play.api.libs.json.{JsObject, Json}
import slick.jdbc.JdbcBackend.Database

import java.sql.Timestamp
import scala.concurrent.duration.*

/**
 * Represents an audit event to be logged.
 *
 * @param tenant Tenant identifier
 * @param userId User who performed the action (optional for system events)
 * @param eventType Event type (e.g., fetch_tools, get, post, login, client_connected)
 * @param description Human-readable event description
 * @param sourceType Source type (e.g., mcp_server, system, client) for event origin classification
 * @param sourceUuid UUID reference to the source entity (no FK constraint for flexibility)
 * @param rawData Additional raw data about the event (stored as JSONB)
 */
case class AuditEvent(
  tenant: String,
  userId: Option[String] = None,
  eventType: String,
  description: Option[String] = None,
  sourceType: Option[String] = None,
  sourceUuid: Option[java.util.UUID] = None,
  rawData: Map[String, String] = Map.empty
) extends TurnstileSerializable {

  /**
   * Convert this AuditEvent to an EventLogRow for database persistence
   */
  def toEventLogRow: EventLogRow = {
    val rawDataJson = if (rawData.nonEmpty) {
      Json.toJson(rawData).as[JsObject]
    } else {
      play.api.libs.json.JsNull
    }

    EventLogRow(
      tenant = tenant,
      userId = userId,
      eventType = eventType,
      description = description,
      sourceType = sourceType,
      sourceUuid = sourceUuid,
      rawData = rawDataJson,
      createdAt = new Timestamp(System.currentTimeMillis())
    )
  }
}

/**
 * Event Log Actor - batches audit events and periodically flushes them to the database.
 *
 * This actor receives EventLog messages containing AuditEvent instances and batches them
 * until either BATCH_SIZE is reached or BATCH_FLUSH_SEC has elapsed.
 *
 * Architecture:
 * - Cluster sharded actor for distributed event logging
 * - Batches events in memory for efficiency
 * - Fixed-rate timer periodically flushes batches
 * - Size-based flushing to prevent unbounded memory growth
 *
 * Lifecycle States:
 * 1. activeState: Collecting events and managing batch lifecycle
 *    - Accumulates events in batch
 *    - Triggers flush when batch size reached
 *    - Timer triggers periodic flush at fixed rate
 *
 * Message Flow:
 * {{{
 * External → EventLogActor.EventLog(AuditEvent)
 *   ↓
 * Add to batch
 *   ↓
 * If batch size >= BATCH_SIZE → FLUSH (timer continues)
 * Or if BATCH_FLUSH_SEC elapsed → FLUSH (if batch non-empty)
 *   ↓
 * flushToDb(batch) → clear batch
 * }}}
 */
object EventLogActor {
  val TypeKey: EntityTypeKey[EventLogActor.Message] =
    EntityTypeKey[EventLogActor.Message]("EventLogActor")

  // Configuration constants
  private val BATCH_SIZE: Int = 10
  private val BATCH_FLUSH_SEC: Int = 10

  def initSharding(
    system: ActorSystem[?],
    db: Database
  ): Unit =
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      EventLogActor(entityContext.entityId, db)
    })

  sealed trait Message extends TurnstileSerializable
  final case class EventLog(event: AuditEvent) extends Message
  private case object FlushTimer extends Message

  /**
   * Internal message for handling flush result
   */
  private final case class FlushResult(success: Boolean, error: Option[String] = None) extends Message

  private def apply(tenant: String, db: Database): Behavior[Message] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        context.log.info(s"Starting EventLogActor for entity $tenant")

        // Start fixed-rate timer that fires every BATCH_FLUSH_SEC seconds
        timers.startTimerAtFixedRate(FlushTimer, FlushTimer, BATCH_FLUSH_SEC.seconds)

        new EventLogActor(context, timers, tenant, db).activeState(Vector.empty)
      }
    }
  }
}

private class EventLogActor(
  context: ActorContext[EventLogActor.Message],
  timers: TimerScheduler[EventLogActor.Message],
  tenant: String,
  db: Database
) {
  import EventLogActor.*

  implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

  /**
   * Active state - collecting events and managing batch lifecycle.
   *
   * The fixed-rate timer runs continuously. Batch is flushed either:
   * 1. When batch size reaches BATCH_SIZE (immediate flush)
   * 2. When timer fires and batch is non-empty (periodic flush)
   *
   * @param batch Current batch of events waiting to be flushed
   * @return Behavior for handling messages
   */
  private def activeState(batch: Vector[AuditEvent]): Behavior[Message] = {
    Behaviors.receiveMessagePartial(
      handleEventLog(batch)
        .orElse(handleFlushTimer(batch))
        .orElse(handleFlushResult())
    )
  }

  private def handleEventLog(
    batch: Vector[AuditEvent]
  ): PartialFunction[Message, Behavior[Message]] = {
    case EventLog(event) =>
      context.log.debug(s"EventLogActor $tenant received event: ${event.eventType} by ${event.userId.getOrElse("system")}")
      val newBatch = batch :+ event

      if (newBatch.size >= BATCH_SIZE) {
        context.log.info(s"EventLogActor $tenant batch size reached ($BATCH_SIZE), flushing ${newBatch.size} events")
        flushToDb(newBatch)
        activeState(Vector.empty)
      } else {
        activeState(newBatch)
      }
  }

  private def handleFlushTimer(
    batch: Vector[AuditEvent]
  ): PartialFunction[Message, Behavior[Message]] = {
    case FlushTimer =>
      if (batch.nonEmpty) {
        context.log.info(s"EventLogActor $tenant flush timer fired, flushing ${batch.size} events")
        flushToDb(batch)
        activeState(Vector.empty)
      } else {
        context.log.debug(s"EventLogActor $tenant flush timer fired but batch is empty")
        Behaviors.same
      }
  }

  private def handleFlushResult(): PartialFunction[Message, Behavior[Message]] = {
    case FlushResult(success, error) =>
      if (success) {
        context.log.debug(s"EventLogActor $tenant successfully flushed events to database")
      } else {
        context.log.error(s"EventLogActor $tenant failed to flush events: ${error.getOrElse("unknown error")}")
      }
      Behaviors.same
  }

  /**
   * Flushes the batch of events to the database.
   * Persists events using batch insert for efficiency.
   *
   * @param batch The batch of events to flush
   */
  private def flushToDb(
    batch: Vector[AuditEvent]
  ): Unit = {
    context.log.info(s"EventLogActor $tenant flushing ${batch.size} events to database")

    // Convert AuditEvents to EventLogRows
    val eventLogRows = batch.map(_.toEventLogRow)

    // Batch insert into database
    val insertAction = Tables.eventLogs ++= eventLogRows
    val future = db.run(insertAction)

    // Pipe result back to self
    context.pipeToSelf(future) {
      case scala.util.Success(result) =>
        context.log.info(s"EventLogActor $tenant successfully persisted ${result.getOrElse(batch.size)} events")
        FlushResult(success = true)
      case scala.util.Failure(ex) =>
        context.log.error(s"EventLogActor $tenant failed to persist events: ${ex.getMessage}", ex)
        FlushResult(success = false, error = Some(ex.getMessage))
    }
  }
}
