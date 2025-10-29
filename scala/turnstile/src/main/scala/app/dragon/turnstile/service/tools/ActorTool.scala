package app.dragon.turnstile.service.tools

import app.dragon.turnstile.service.{McpTool, SyncToolHandler}
import io.modelcontextprotocol.server.McpAsyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.{Flux, Mono}
import java.time.Duration

/**
 * Actor tool - demonstrates streaming/async capabilities with a simple actor-style message response.
 *
 * This tool provides a simple demonstration of reactive/streaming patterns in MCP:
 * - Accepts an optional message parameter and count for repetition
 * - Simulates async processing with reactive composition
 * - Returns a formatted response showing the actor system interaction
 *
 * This demonstrates:
 * - Non-blocking async execution using Reactor Mono
 * - Reactive composition patterns (when used with getAsyncToolsSpec)
 * - How to build tools that can scale with backpressure
 * - Message processing simulation that could be replaced with real actor interactions
 *
 * The tool simulates an actor sending messages and receiving responses,
 * demonstrating how the MCP async API allows for reactive, non-blocking tool execution.
 * When integrated with the async MCP server, this handler can be wrapped in a Mono
 * that uses delays, transformations, and other reactive operators.
 *
 * Example usage:
 * {{{
 * {
 *   "name": "actor_tool",
 *   "arguments": {
 *     "message": "Hello from client",
 *     "count": 3
 *   }
 * }
 * }}}
 *
 * Returns: A formatted response showing the actor interaction
 *
 * @note This tool demonstrates the async/streaming nature through the MCP async API.
 *       The handler itself is synchronous, but when wrapped in a Mono by getAsyncToolsSpec(),
 *       it becomes part of a reactive pipeline that supports non-blocking execution,
 *       composition with delays/timeouts, and backpressure handling.
 *       For true multi-message streaming, use Server-Sent Events (SSE) via the streamable transport.
 */
class ActorTool extends McpToolProvider {

  override def tool: McpTool = {
    val schema = createToolSchemaBuilder(
      name = "actor_tool",
      description = "Demonstrates streaming/async capabilities with actor-style messaging"
    )
      .inputSchema(createObjectSchema(
        properties = Map(
          "message" -> Map(
            "type" -> "string",
            "description" -> "The message to send to the actor"
          ),
          "count" -> Map(
            "type" -> "integer",
            "description" -> "Number of times to process the message (1-10)"
          )
        ),
        required = Seq() // All parameters are optional
      ))
      .build()

    val handler: SyncToolHandler = (_, request) => {
      val message = getStringArg(request, "message", "ping")
      val count = Math.min(Math.max(getIntArg(request, "count", 1), 1), 10) // Clamp to 1-10

      logger.debug(s"Actor tool called with message: '$message', count: $count")

      // Simulate actor-style processing
      val actorName = "TurnstileActor"
      val timestamp = System.currentTimeMillis()

      // Build a multi-part response that demonstrates streaming-style data composition
      val headerParts = Seq(
        s"🎭 Actor System Response (Streaming Demo)",
        s"Actor: $actorName",
        s"Timestamp: $timestamp",
        s"Message Count: $count",
        s"",
        s"📨 Received Message: \"$message\"",
        s""
      ) 

      // Simulate stream of message processing
      val streamParts = (1 to count).flatMap { i =>
        Seq(
          s"[Stream $i/$count] Processing...",
          s"  ├─ Message validated",
          s"  ├─ Actor state updated",
          s"  └─ Response: \"$actorName processed '$message' (iteration $i)\"",
          if (i < count) "" else s""
        )
      }

      val footerParts = Seq(
        s"✅ Processing Complete",
        s"   Total messages processed: $count",
        s"   Status: SUCCESS",
        s"",
        s"ℹ️  Streaming Note:",
        s"   This tool demonstrates async/reactive patterns. The handler is wrapped in a Mono",
        s"   by the MCP async server, enabling non-blocking execution, reactive composition,",
        s"   and backpressure handling. For true multi-message streaming over time,",
        s"   use Server-Sent Events (SSE) via the streamable HTTP transport."
      )

      val response = (headerParts ++ streamParts ++ footerParts).mkString("\n")

      createTextResult(response)
    }

    // Async handler sends notifications for each step
    val asyncHandler: (McpAsyncServerExchange, McpSchema.CallToolRequest) => Mono[McpSchema.CallToolResult] =
      (exchange, request) => {
        val message = getStringArg(request, "message", "ping")
        val count = Math.min(Math.max(getIntArg(request, "count", 1), 1), 10)
        val actorName = "TurnstileActor"
        val startTime = System.currentTimeMillis()

        // Create a unique progress token that includes tool name and timestamp for correlation
        val progressToken = s"actor-tool-${request.name()}-$startTime"

        logger.info(s"[ActorTool] Starting streaming processing: message='$message', count=$count, token=$progressToken")

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
          s"[$actorName] Starting processing: '$message' (0/$count)",
          correlationMeta
        )

        // Emit a progress notification for each step, with 2 second delays
        val notificationFlux = Mono.fromRunnable(() =>
          exchange.progressNotification(initialNotification).subscribe()
        ).thenMany(
          Flux.range(1, count)
            .delayElements(Duration.ofSeconds(2))
            .doOnNext(i => logger.info(s"[ActorTool] Processing iteration $i/$count (after 2s delay)"))
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
                s"[$actorName] Processed '$message' ($i/$count) - ${elapsed}s elapsed",
                iterationMeta
              )

              exchange.progressNotification(notification)
                .doOnSuccess(_ => logger.info(s"[ActorTool] Sent progress notification: $i/$count"))
            }
        )

        // After all notifications, return the final result
        notificationFlux.`then`(
          Mono.fromSupplier(() => {
            val endTime = System.currentTimeMillis()
            val totalElapsed = (endTime - startTime) / 1000.0

            logger.info(s"[ActorTool] Streaming complete: $count iterations in ${totalElapsed}s")

            val headerParts = Seq(
              s"🎭 Actor System Response (TRUE STREAMING with 2s delays)",
              s"Actor: $actorName",
              s"Start Time: $startTime",
              s"End Time: $endTime",
              s"Total Duration: ${totalElapsed}s",
              s"Message Count: $count",
              s"Progress Token: $progressToken",
              s"",
              s"📨 Received Message: \"$message\"",
              s""
            )
            val streamParts = (1 to count).flatMap { i =>
              Seq(
                s"[Stream $i/$count] Processing...",
                s"  ├─ Message validated",
                s"  ├─ Actor state updated",
                s"  └─ Response: \"$actorName processed '$message' (iteration $i)\"",
                if (i < count) "" else s""
              )
            }
            val footerParts = Seq(
              s"✅ Processing Complete",
              s"   Total messages processed: $count",
              s"   Total duration: ${totalElapsed}s (expected: ~${count * 2}s)",
              s"   Status: SUCCESS",
              s"",
              s"📊 Progress Notifications:",
              s"   - Progress Token: $progressToken",
              s"   - Notifications sent: ${count + 1} (initial + $count iterations)",
              s"   - Each notification includes correlation metadata:",
              s"     • toolName: ${request.name()}",
              s"     • startTime: $startTime",
              s"     • iteration: (current iteration number)",
              s"     • elapsedSeconds: (time elapsed for that iteration)",
              s"",
              s"ℹ️  TRUE Streaming Demo:",
              s"   - Real 2-second delays using Flux.delayElements(Duration.ofSeconds(2))",
              s"   - Progress notifications sent via SSE with correlation metadata",
              s"   - Non-blocking execution on Reactor scheduler",
              s"   - Clients can correlate notifications using progressToken + metadata"
            )
            val response = (headerParts ++ streamParts ++ footerParts).mkString("\n")
            createTextResult(response)
          })
        )
      }

    McpTool(
      name = "actor_tool",
      description = "Demonstrates streaming/async capabilities with actor-style messaging",
      schema = schema,
      syncHandler = handler,
      asyncHandler = asyncHandler
    )
  }
}

object ActorTool {
  /**
   * Create a new ActorTool instance
   */
  def apply(): ActorTool = new ActorTool()
}
