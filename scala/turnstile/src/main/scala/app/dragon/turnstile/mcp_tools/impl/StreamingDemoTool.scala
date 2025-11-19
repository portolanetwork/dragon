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

package app.dragon.turnstile.mcp_tools.impl

import app.dragon.turnstile.mcp_tools.{AsyncToolHandler, McpTool, McpUtils}
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.{Flux, Mono}

import java.time.Duration

/**
 * Streaming Demo tool - demonstrates streaming/async capabilities with progress notifications.
 *
 * This tool provides a simple demonstration of reactive/streaming patterns in MCP:
 * - Accepts an optional message parameter and count for repetition
 * - Simulates async processing with reactive composition
 * - Returns a formatted response showing the streaming system interaction
 *
 * This demonstrates:
 * - Non-blocking async execution using Reactor Mono
 * - Reactive composition patterns (when used with getAsyncToolsSpec)
 * - How to build tools that can scale with backpressure
 * - Message processing simulation with real-time progress notifications
 *
 * The tool simulates streaming message processing with real-time feedback,
 * demonstrating how the MCP async API allows for reactive, non-blocking tool execution.
 * When integrated with the async MCP server, this handler can be wrapped in a Mono
 * that uses delays, transformations, and other reactive operators.
 *
 * Example usage:
 * {{{
 * {
 *   "name": "streaming_demo_tool",
 *   "arguments": {
 *     "message": "Hello from client",
 *     "count": 3
 *   }
 * }
 * }}}
 *
 * Returns: A formatted response showing the streaming interaction
 *
 * @note This tool demonstrates the async/streaming nature through the MCP async API.
 *       The handler itself is synchronous, but when wrapped in a Mono by getAsyncToolsSpec(),
 *       it becomes part of a reactive pipeline that supports non-blocking execution,
 *       composition with delays/timeouts, and backpressure handling.
 *       For true multi-message streaming, use Server-Sent Events (SSE) via the streamable transport.
 */
object StreamingDemoTool extends McpTool {

  override def getSchema(): McpSchema.Tool = {
    McpUtils.createToolSchemaBuilder(
        name = "streaming_demo_tool",
        description = "Demonstrates streaming/async capabilities with progress notifications"
      )
      .inputSchema(McpUtils.createObjectSchema(
        properties = Map(
          "message" -> Map(
            "type" -> "string",
            "description" -> "The message to process in the streaming demo"
          ),
          "count" -> Map(
            "type" -> "integer",
            "description" -> "Number of times to process the message (1-10)"
          )
        ),
        required = Seq() // All parameters are optional
      ))
      .build()
  }

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request) => {
      val message = McpUtils.getStringArg(request, "message", "ping")
      val count = Math.min(Math.max(McpUtils.getIntArg(request, "count", 1), 1), 10)
      val streamingDemoName = "TurnstileStreamingDemo"
      val startTime = System.currentTimeMillis()
      // Per spec, use the request's progress token if provided
      val progressToken = request.progressToken()

      logger.info(s"[StreamingDemoTool] Starting streaming processing: message='$message', count=$count, token=$progressToken")

      // Create metadata for correlation with tool call
      val correlationMeta = java.util.Map.of[String, Object](
        "toolName", request.name(),
        "startTime", Long.box(startTime),
        "message", message,
        "totalCount", Integer.valueOf(count)
      )

      // Send initial progress notification
      val initialNotification = new McpSchema.ProgressNotification(
        progressToken,
        Double.box(0.0),
        Double.box(count.toDouble),
        s"[$streamingDemoName] Starting processing: '$message' (0/$count)",
        correlationMeta
      )

      // Emit a progress notification for each step, with 2 second delays
      exchange.progressNotification(initialNotification)
        .doOnSuccess(_ => logger.info(s"[StreamingDemoTool] Sent initial progress notification: 0/$count"))
        .thenMany(
          Flux.range(1, count)
            .delayElements(Duration.ofSeconds(2))
            .doOnNext(i => logger.info(s"[StreamingDemoTool] Processing iteration $i/$count (after 2s delay)"))
            .flatMap { i =>
              val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
              val iterationMeta = java.util.Map.of[String, Object](
                "toolName", request.name(),
                "startTime", Long.box(startTime),
                "iteration", Integer.valueOf(i),
                "elapsedSeconds", Double.box(elapsed)
              )

              val notification = new McpSchema.ProgressNotification(
                progressToken,
                Double.box(i.toDouble),
                Double.box(count.toDouble),
                s"[$streamingDemoName] Processed '$message' ($i/$count) - ${elapsed}s elapsed",
                iterationMeta
              )

              exchange.progressNotification(notification)
                .doOnSuccess(_ => logger.info(s"[StreamingDemoTool] Sent progress notification: $i/$count"))
            }
        ).`then`(
          Mono.fromSupplier(() => {
            val endTime = System.currentTimeMillis()
            val totalElapsed = (endTime - startTime) / 1000.0

            logger.info(s"[StreamingDemoTool] Streaming complete: $count iterations in ${totalElapsed}s")

            val response1 = "This is the final response"

            McpUtils.createTextResult(response1)
          })
        )
    }
  }
}
