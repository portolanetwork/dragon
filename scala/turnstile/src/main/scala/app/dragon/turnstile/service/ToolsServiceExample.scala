package app.dragon.turnstile.service

import com.typesafe.config.{Config, ConfigFactory}
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*

/**
 * Example usage of ToolsService for dynamic tool registration.
 *
 * This demonstrates:
 * 1. Creating a ToolsService
 * 2. Defining custom tools with JSON schemas
 * 3. Registering tools for specific users
 * 4. Retrieving and using tools
 * 5. Error handling and validation
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
    val toolsService = ToolsService(config)

    // Check default tools
    val defaultTools = toolsService.getDefaultTools
    logger.info(s"Default tools: ${defaultTools.map(_.name).mkString(", ")}")

    // Register custom tools for a user
    val userId = "user123"
    val customTools = List(
      createGreetingTool,
      createCalculatorTool,
      createWeatherTool
    )

    toolsService.updateTools(userId, customTools) match {
      case Right(count) =>
        logger.info(s"Successfully registered $count custom tools for $userId")
      case Left(errors) =>
        logger.error(s"Failed to register tools: ${errors.mkString(", ")}")
    }

    // Get all tools for the user
    val allTools = toolsService.getToolsForUser(userId)
    logger.info(s"User $userId has ${allTools.size} tools: ${allTools.map(_.name).mkString(", ")}")

    // Create an MCP service for the user
    val userService = toolsService.createServiceForUser(userId)
    logger.info(s"Created MCP service with ${userService.tools.size} tools")

    // Get statistics
    val stats = toolsService.getStats
    logger.info(s"Service statistics: $stats")
  }

  /**
   * Demonstrate error handling
   */
  def demonstrateErrorHandling(config: Config): Unit = {
    logger.info("=== ToolsService Error Handling Example ===")

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val toolsService = ToolsService(config)
    val userId = "user456"

    // Example 1: Invalid JSON schema
    val invalidSchemaTool = DynamicTool(
      name = "invalid_schema",
      description = "Tool with invalid schema",
      schemaJson = "{invalid json",
      handler = DynamicTool.simpleTextHandler("test")
    )

    toolsService.updateTools(userId, List(invalidSchemaTool)) match {
      case Right(_) =>
        logger.error("Should have failed with invalid schema")
      case Left(errors) =>
        logger.info(s"Expected validation error: ${errors.mkString(", ")}")
    }

    // Example 2: Duplicate tool names
    val tool1 = createGreetingTool
    val tool2 = createGreetingTool.copy(description = "Another greeting")

    toolsService.updateTools(userId, List(tool1, tool2)) match {
      case Right(_) =>
        logger.error("Should have failed with duplicate names")
      case Left(errors) =>
        logger.info(s"Expected duplicate error: ${errors.mkString(", ")}")
    }

    // Example 3: Conflicting with default tool names
    val conflictingTool = DynamicTool(
      name = "echo", // Conflicts with default echo tool
      description = "Conflicts with default",
      schemaJson = """{"type": "object"}""",
      handler = DynamicTool.simpleTextHandler("test")
    )

    toolsService.updateTools(userId, List(conflictingTool)) match {
      case Right(_) =>
        logger.error("Should have failed with name conflict")
      case Left(errors) =>
        logger.info(s"Expected conflict error: ${errors.mkString(", ")}")
    }
  }

  /**
   * Demonstrate tool replacement and clearing
   */
  def demonstrateToolManagement(config: Config): Unit = {
    logger.info("=== ToolsService Tool Management Example ===")

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val toolsService = ToolsService(config)
    val userId = "user789"

    // Register initial tools
    toolsService.updateTools(userId, List(createGreetingTool)) match {
      case Right(_) => logger.info(s"Registered initial tools")
      case Left(errors) => logger.error(s"Failed: ${errors.mkString(", ")}")
    }

    logger.info(s"Tools after initial registration: ${toolsService.getToolNamesForUser(userId).mkString(", ")}")

    // Replace with new tools
    toolsService.updateTools(userId, List(createCalculatorTool, createWeatherTool)) match {
      case Right(_) => logger.info(s"Replaced with new tools")
      case Left(errors) => logger.error(s"Failed: ${errors.mkString(", ")}")
    }

    logger.info(s"Tools after replacement: ${toolsService.getToolNamesForUser(userId).mkString(", ")}")

    // Clear all custom tools
    val cleared = toolsService.clearUserTools(userId)
    logger.info(s"Cleared $cleared custom tools")
    logger.info(s"Tools after clearing: ${toolsService.getToolNamesForUser(userId).mkString(", ")}")
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
