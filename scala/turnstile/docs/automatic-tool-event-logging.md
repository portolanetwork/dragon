# Automatic Event Logging for MCP Tools

## Overview

All MCP tools now automatically log execution events without requiring manual logging code in individual tool implementations. This is achieved through a decorator pattern in the `McpTool` trait.

## How It Works

### 1. Wrapper Method in McpTool

The `McpTool` trait now provides `getAsyncHandlerWithLogging()` method that wraps the tool's handler with automatic event logging:

```scala
def getAsyncHandlerWithLogging(
  userId: String,
  tenant: String = "default"
)(
  implicit sharding: ClusterSharding
): AsyncToolHandler
```

This wrapper:
- Measures execution time
- Logs successful executions with `TOOL_EXECUTED` event type
- Logs failures with `TOOL_EXECUTION_FAILED` event type
- Uses `EventData.ToolExecutionData` for structured event data

### 2. ToolsService Integration

`ToolsService` automatically uses the wrapped handler when converting tools to specifications:

```scala
def getDefaultToolsSpec()(
  implicit sharding: ClusterSharding
): List[AsyncToolSpecification]
```

The `convertToAsyncToolSpec` method checks if logging is enabled and uses the appropriate handler.

### 3. Event Data Structure

Each tool execution creates a `ToolExecutionData` event with:
- `toolName`: Name of the tool executed
- `executionTimeMs`: How long the tool took to execute
- `success`: Whether execution succeeded
- `errorMessage`: Error details if execution failed (optional)

## Usage

### For Tool Implementers

**No code changes required!** Simply implement your tool as before:

```scala
class MyTool extends McpTool {
  override def getSchema(): McpSchema.Tool = {
    McpUtils.createToolSchemaBuilder("my_tool", "My tool description")
      .inputSchema(/* ... */)
      .build()
  }

  override def getAsyncHandler(): AsyncToolHandler = {
    (exchange, request) => {
      // Your tool logic here
      Mono.just(McpUtils.createTextResult("Result"))
    }
  }
}
```

Event logging happens automatically when the tool is executed.

### For System Integration

When registering tools in `McpServerActor`, ensure `ClusterSharding` is available:

```scala
implicit val sharding: ClusterSharding = ClusterSharding(system)

ToolsService.getInstance(userId).getDefaultToolsSpec().foreach { toolSpec =>
  mcpServer.addTool(toolSpec)
}
```

## Event Log Schema

Events are logged to the `event_log` table with:
- **tenant**: "default"
- **userId**: User who executed the tool
- **eventType**: "TOOL_EXECUTED" or "TOOL_EXECUTION_FAILED"
- **description**: Human-readable description
- **metadata** (JSONB):
  ```json
  {
    "toolName": "echo1",
    "executionTimeMs": 42,
    "success": true,
    "errorMessage": "optional error details"
  }
  ```

## Benefits

✅ **Zero boilerplate**: Tool implementers don't write logging code
✅ **Consistent metrics**: All tools logged uniformly
✅ **Performance tracking**: Execution time automatically measured
✅ **Error tracking**: Failures captured with full context
✅ **Type-safe**: Uses sealed trait `EventData` instead of raw Map
✅ **Batched writes**: EventLogActor batches events for efficiency

## Example Event Flow

```
User calls tool "echo1"
    ↓
getAsyncHandlerWithLogging wraps the handler
    ↓
Tool executes (success or failure)
    ↓
doOnSuccess/doOnError callback fires
    ↓
Event sent to EventLogActor
    ↓
EventLogActor batches event
    ↓
After 10 seconds or 10 events, batch flushed to database
    ↓
Event persisted in event_log table
```

## Migration Notes

Existing tools automatically benefit from this feature - no migration required. The logging is transparent and doesn't affect tool behavior.

## Configuration

Event batching configuration in `EventLogActor`:
- **BATCH_SIZE**: 10 events
- **BATCH_FLUSH_SEC**: 10 seconds

Events are flushed when either threshold is reached.
