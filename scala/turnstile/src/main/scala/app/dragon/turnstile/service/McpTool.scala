package app.dragon.turnstile.service

import app.dragon.turnstile.service.{AsyncToolHandler, SyncToolHandler}
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

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
 * Example:
 * {{{
 * import app.dragon.turnstile.service.McpUtils.*
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
  def getAsyncHandler(): AsyncToolHandler
}
