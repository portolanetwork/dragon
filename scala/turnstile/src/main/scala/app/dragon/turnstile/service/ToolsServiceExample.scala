package app.dragon.turnstile.service

import com.typesafe.config.{Config, ConfigFactory}
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*

/**
 * Example usage of ToolsService for dynamic tool registration.
 *
 * This demonstrates:
 * 1. Creating and using the ToolsService singleton
 * 2. Defining custom tools with JSON schemas
 * 3. CRUD operations: createTool, getTool, getTools, deleteTool
 * 4. Error handling and validation
 * 5. Working with tool IDs for identification
 */
object ToolsServiceExample {
  private val logger: Logger = LoggerFactory.getLogger(ToolsServiceExample.getClass)

  /**
   * Example 1: Simple text response tool
   */
  def createGreetingTool: DynamicTool = {
    val schemaJson =
      """{
        |  "type": "object",
        |  "properties": {
        |    "name": {
        |      "type": "string",
        |      "description": "The name to greet"
        |    }
        |  },
        |  "required": ["name"]
        |}""".stripMargin

    val handler: ToolHandler = (_, request) => {
      val name = Option(request.arguments())
        .flatMap(args => Option(args.get("name")))
        .map(_.toString)
        .getOrElse("World")

      val content: java.util.List[McpSchema.Content] = List(
        new McpSchema.TextContent(s"Hello, $name!")
      ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

      McpSchema.CallToolResult.builder()
        .content(content)
        .isError(false)
        .build()
    }

    DynamicTool(
      name = "greeting",
      description = "Greet a person by name",
      schemaJson = schemaJson,
      handler = handler
    )
  }

  /**
   * Example 2: Calculator tool with multiple operations
   */
  def createCalculatorTool: DynamicTool = {
    val schemaJson =
      """{
        |  "type": "object",
        |  "properties": {
        |    "operation": {
        |      "type": "string",
        |      "description": "Operation to perform: add, subtract, multiply, divide",
        |      "enum": ["add", "subtract", "multiply", "divide"]
        |    },
        |    "a": {
        |      "type": "number",
        |      "description": "First number"
        |    },
        |    "b": {
        |      "type": "number",
        |      "description": "Second number"
        |    }
        |  },
        |  "required": ["operation", "a", "b"]
        |}""".stripMargin

    val handler: ToolHandler = (_, request) => {
      val args = Option(request.arguments()).getOrElse(java.util.Collections.emptyMap())

      val operation = Option(args.get("operation")).map(_.toString).getOrElse("")
      val a = Option(args.get("a")).map(_.toString.toDouble).getOrElse(0.0)
      val b = Option(args.get("b")).map(_.toString.toDouble).getOrElse(0.0)

      operation match {
        case "divide" if b == 0 =>
          val errorContent: java.util.List[McpSchema.Content] = List(
            new McpSchema.TextContent("Error: Division by zero")
          ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

          McpSchema.CallToolResult.builder()
            .content(errorContent)
            .isError(true)
            .build()

        case op @ ("add" | "subtract" | "multiply" | "divide") =>
          val result = op match {
            case "add" => a + b
            case "subtract" => a - b
            case "multiply" => a * b
            case "divide" => a / b
          }

          val content: java.util.List[McpSchema.Content] = List(
            new McpSchema.TextContent(s"$a $op $b = $result")
          ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

          McpSchema.CallToolResult.builder()
            .content(content)
            .isError(false)
            .build()

        case _ =>
          val errorContent: java.util.List[McpSchema.Content] = List(
            new McpSchema.TextContent(s"Error: Unknown operation: $operation")
          ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

          McpSchema.CallToolResult.builder()
            .content(errorContent)
            .isError(true)
            .build()
      }
    }

    DynamicTool(
      name = "calculator",
      description = "Perform basic arithmetic operations",
      schemaJson = schemaJson,
      handler = handler
    )
  }

  /**
   * Example 3: Weather lookup tool (mock implementation)
   */
  def createWeatherTool: DynamicTool = {
    val schemaJson =
      """{
        |  "type": "object",
        |  "properties": {
        |    "city": {
        |      "type": "string",
        |      "description": "City name"
        |    },
        |    "unit": {
        |      "type": "string",
        |      "description": "Temperature unit: celsius or fahrenheit",
        |      "enum": ["celsius", "fahrenheit"]
        |    }
        |  },
        |  "required": ["city"]
        |}""".stripMargin

    val handler: ToolHandler = (_, request) => {
      val args = Option(request.arguments()).getOrElse(java.util.Collections.emptyMap())

      val city = Option(args.get("city")).map(_.toString).getOrElse("Unknown")
      val unit = Option(args.get("unit")).map(_.toString).getOrElse("celsius")

      // Mock weather data
      val temp = if (unit == "fahrenheit") "72°F" else "22°C"
      val weather = s"Weather in $city: Sunny, $temp"

      val content: java.util.List[McpSchema.Content] = List(
        new McpSchema.TextContent(weather)
      ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

      McpSchema.CallToolResult.builder()
        .content(content)
        .isError(false)
        .build()
    }

    DynamicTool(
      name = "weather",
      description = "Get weather information for a city",
      schemaJson = schemaJson,
      handler = handler
    )
  }

  /**
   * Demonstrate basic usage
   */
  def demonstrateBasicUsage(config: Config): Unit = {
    logger.info("=== ToolsService Basic Usage Example ===")

    // Create the service with global execution context
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val toolsService = ToolsService.instance

    // Check default tools
    val defaultTools = toolsService.getDefaultTools
    logger.info(s"Default tools: ${defaultTools.map(_.name).mkString(", ")}")

    // Create custom tools for a user
    val userId = "user123"
    val customTools = List(
      createGreetingTool,
      createCalculatorTool,
      createWeatherTool
    )

    // Create each tool individually
    customTools.foreach { tool =>
      toolsService.createTool(userId, tool).map {
        case Right(created) =>
          logger.info(s"Successfully created tool ${created.name} with id ${created.id} for $userId")
        case Left(err) =>
          logger.error(s"Failed to create tool ${tool.name}: ${err.message}")
      }
    }

    // Get all tools for the user (asynchronously)
    toolsService.getTools(userId).map {
      case Right(allTools) =>
        logger.info(s"User $userId has ${allTools.size} tools: ${allTools.map(_.name).mkString(", ")}")
      case Left(err) =>
        logger.error(s"Failed to get tools: ${err.message}")
    }
  }

  /**
   * Demonstrate error handling
   */
  def demonstrateErrorHandling(config: Config): Unit = {
    logger.info("=== ToolsService Error Handling Example ===")

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val toolsService = ToolsService.instance
    val userId = "user456"

    // Example 1: Invalid JSON schema
    val invalidSchemaTool = DynamicTool(
      name = "invalid_schema",
      description = "Tool with invalid schema",
      schemaJson = "{invalid json",
      handler = DynamicTool.simpleTextHandler("test")
    )

    toolsService.createTool(userId, invalidSchemaTool).map {
      case Right(_) =>
        logger.error("Should have failed with invalid schema")
      case Left(err) =>
        logger.info(s"Expected validation error: ${err.message}")
    }

    // Example 2: Conflicting with default tool names
    val conflictingTool = DynamicTool(
      name = "echo", // Conflicts with default echo tool
      description = "Conflicts with default",
      schemaJson = """{"type": "object"}""",
      handler = DynamicTool.simpleTextHandler("test")
    )

    toolsService.createTool(userId, conflictingTool).map {
      case Right(_) =>
        logger.error("Should have failed with name conflict")
      case Left(err) =>
        logger.info(s"Expected conflict error: ${err.message}")
    }

    // Example 3: Get non-existent tool
    toolsService.getTool(userId, 99999L).map {
      case Right(_) =>
        logger.error("Should have failed - tool doesn't exist")
      case Left(err) =>
        logger.info(s"Expected not found error: ${err.message}")
    }

    // Example 4: Delete non-existent tool
    toolsService.deleteTool(userId, 99999L).map {
      case Right(_) =>
        logger.error("Should have failed - tool doesn't exist")
      case Left(err) =>
        logger.info(s"Expected not found error: ${err.message}")
    }
  }

  /**
   * Demonstrate tool CRUD operations
   */
  def demonstrateToolManagement(config: Config): Unit = {
    logger.info("=== ToolsService Tool Management Example ===")

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val toolsService = ToolsService.instance
    val userId = "user789"

    // Create a tool
    val greetingFuture = toolsService.createTool(userId, createGreetingTool).map {
      case Right(created) =>
        logger.info(s"Created tool ${created.name} with id ${created.id}")
        created.id.get // Return the id
      case Left(err) =>
        logger.error(s"Failed to create tool: ${err.message}")
        0L
    }

    // After creating, get all tools
    greetingFuture.flatMap { toolId =>
      toolsService.getTools(userId).map {
        case Right(allTools) =>
          logger.info(s"Tools after creation: ${allTools.map(_.name).mkString(", ")}")
        case Left(err) =>
          logger.error(s"Failed to get tools: ${err.message}")
      }

      // Get the specific tool by id
      toolsService.getTool(userId, toolId).map {
        case Right(tool) =>
          logger.info(s"Retrieved tool: ${tool.name}")
        case Left(err) =>
          logger.error(s"Failed to get tool: ${err.message}")
      }

      // Delete the tool
      toolsService.deleteTool(userId, toolId).map {
        case Right(count) =>
          logger.info(s"Deleted $count tool(s)")
        case Left(err) =>
          logger.error(s"Failed to delete tool: ${err.message}")
      }

      // Verify deletion
      toolsService.getTool(userId, toolId).map {
        case Right(_) =>
          logger.error("Tool should have been deleted")
        case Left(err) =>
          logger.info(s"Confirmed deletion: ${err.message}")
      }
    }
  }

  /**
   * Main demonstration method
   */
  def main(args: Array[String]): Unit = {
    // Create a test configuration
    val config = ConfigFactory.parseString(
      """
        |name = "Turnstile Test Server"
        |version = "1.0.0-test"
        |""".stripMargin
    )

    try {
      demonstrateBasicUsage(config)
      println()
      demonstrateErrorHandling(config)
      println()
      demonstrateToolManagement(config)
    } catch {
      case ex: Exception =>
        logger.error("Example failed", ex)
    }
  }
}
