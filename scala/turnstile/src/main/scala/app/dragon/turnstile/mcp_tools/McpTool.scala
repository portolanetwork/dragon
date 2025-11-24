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

package app.dragon.turnstile.mcp_tools

import app.dragon.turnstile.monitoring.{AuditEvent, EventData, EventLogActor}
import app.dragon.turnstile.utils.ActorLookup
import io.modelcontextprotocol.spec.McpSchema
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.Mono

import scala.jdk.CollectionConverters.*

/**
 * Base trait for MCP tool providers.
 *
 * Tool providers encapsulate the definition and implementation of individual MCP tools,
 * promoting separation of concerns and making tools independently testable and composable.
 *
 * Each tool provider should:
 * 1. Define the tool schema (name, description, input schema)
 * 2. Implement the handler logic
 * 3. Use McpUtils for common utility functions (schema creation, argument extraction, etc.)
 *
 * Event logging is automatically handled - you don't need to add logging code to individual tools.
 *
 * Example:
 * {{{
 * import app.dragon.turnstile.mcp_tools.McpUtils.*
 *
 * object MyCustomTool extends McpTool {
 *   override def getSchema(): McpSchema.Tool = {
 *     createToolSchemaBuilder("my_tool", "Description")
 *       .inputSchema(createObjectSchema())
 *       .build()
 *   }
 *
 *   override def getAsyncHandler(): AsyncToolHandler = {
 *     (exchange, request) => {
 *       val message = getStringArg(request, "message")
 *       Mono.just(createTextResult("Result"))
 *     }
 *   }
 * }
 * }}}
 */
trait McpTool {
  /**
   * Logger instance for the tool provider
   */
  protected val logger: Logger = LoggerFactory.getLogger(this.getClass)

  def getName(): String = getSchema().name()
  def getSchema(): McpSchema.Tool

  /**
   * Implement this method to define the tool's execution logic.
   * Event logging will be automatically wrapped around this handler.
   */
  def getAsyncHandler(): AsyncToolHandler

  /**
   * Returns a handler wrapped with automatic event logging.
   * This is used by ToolsService to register tools with event tracking.
   *
   * @param userId The user ID for event attribution
   * @param sharding ClusterSharding instance for accessing EventLogActor
   * @return AsyncToolHandler with automatic event logging
   */
  def getAsyncHandlerWithLogging(
    userId: String,
    tenant: String = "default"
  )(
    implicit sharding: ClusterSharding
  ): AsyncToolHandler = {
    val baseHandler = getAsyncHandler()
    val toolName = getName()

    (exchange, request) => {
      val startTime = System.currentTimeMillis()

      baseHandler(exchange, request)
        .doOnSuccess { result =>
          val executionTime = System.currentTimeMillis() - startTime

          // Log successful tool execution
          ActorLookup.getEventLogActor(tenant) ! EventLogActor.EventLog(
            AuditEvent(
              tenant = tenant,
              userId = Some(userId),
              eventType = "TOOL_EXECUTED",
              description = Some(s"Tool '$toolName' executed successfully"),
              metadata = EventData.ToolExecutionData(
                toolName = toolName,
                executionTimeMs = executionTime,
                success = true
              )
            )
          )
        }
        .doOnError { error =>
          val executionTime = System.currentTimeMillis() - startTime

          // Log failed tool execution
          ActorLookup.getEventLogActor(tenant) ! EventLogActor.EventLog(
            AuditEvent(
              tenant = tenant,
              userId = Some(userId),
              eventType = "TOOL_EXECUTION_FAILED",
              description = Some(s"Tool '$toolName' execution failed: ${error.getMessage}"),
              metadata = EventData.ToolExecutionData(
                toolName = toolName,
                executionTimeMs = executionTime,
                success = false,
                errorMessage = Some(error.getMessage)
              )
            )
          )
        }
    }
  }
}
