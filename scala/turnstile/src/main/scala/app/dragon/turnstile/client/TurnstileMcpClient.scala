package app.dragon.turnstile.client

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * HTTP-based MCP Client for testing the Turnstile MCP Server.
 * Demonstrates how to connect to the HTTP MCP server and invoke tools.
 */
object TurnstileMcpClient {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private val jsonMapper = new ObjectMapper()
  private val httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def main(args: Array[String]): Unit = {
    logger.info("Starting Turnstile MCP HTTP Client...")

    // Parse command line arguments
    val serverUrl = if (args.nonEmpty) args(0) else "http://localhost:8081/mcp"

    logger.info(s"Connecting to MCP server at: $serverUrl")

    Try {
      // Check server info
      logger.info("\n=== Getting Server Info ===")
      getServerInfo(serverUrl)

      // Initialize connection
      logger.info("\n=== Initializing Connection ===")
      initializeConnection(serverUrl)

      // List available tools
      logger.info("\n=== Listing Available Tools ===")
      listTools(serverUrl)

      // Test echo tool
      logger.info("\n=== Testing Echo Tool ===")
      testEchoTool(serverUrl)

      // Test system_info tool
      logger.info("\n=== Testing System Info Tool ===")
      testSystemInfoTool(serverUrl)

      logger.info("\n=== All Tests Completed Successfully ===")

    } match {
      case Success(_) =>
        logger.info("MCP Client test completed successfully")
      case Failure(exception) =>
        logger.error("MCP Client test failed", exception)
        System.exit(1)
    }
  }

  /**
   * Get server information via GET request
   */
  private def getServerInfo(serverUrl: String): Unit = {
    val request = HttpRequest.newBuilder()
      .uri(URI.create(serverUrl))
      .GET()
      .timeout(Duration.ofSeconds(5))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() == 200) {
      val info = jsonMapper.readTree(response.body())
      if (info.has("server")) logger.info(s"Server: ${info.get("server").asText()}")
      else logger.warn("Server field missing in response")
      if (info.has("version")) logger.info(s"Version: ${info.get("version").asText()}")
      else logger.warn("Version field missing in response")
      if (info.has("transport")) logger.info(s"Transport: ${info.get("transport").asText()}")
      else logger.warn("Transport field missing in response")
      if (info.has("endpoint")) logger.info(s"Endpoint: ${info.get("endpoint").asText()}")
      else logger.warn("Endpoint field missing in response")
    } else {
      logger.error(s"Failed to get server info. Status: ${response.statusCode()}")
    }
  }

  /**
   * Initialize the MCP connection
   */
  private def initializeConnection(serverUrl: String): Unit = {
    val initRequest = Map(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "initialize",
      "params" -> Map(
        "protocolVersion" -> "2024-11-05",
        "capabilities" -> Map[String, Any](),
        "clientInfo" -> Map(
          "name" -> "turnstile-test-client",
          "version" -> "1.0.0"
        )
      ).asJava
    ).asJava

    val response = sendJsonRpcRequest(serverUrl, initRequest.asInstanceOf[java.util.Map[String, Any]])

    if (response.has("result")) {
      val result = response.get("result")
      if (result.has("protocolVersion"))
        logger.info(s"Protocol Version: ${result.get("protocolVersion").asText()}")
      else logger.warn("protocolVersion missing in result")

      val serverInfo = result.get("serverInfo")
      if (serverInfo != null && serverInfo.has("name") && serverInfo.has("version"))
        logger.info(s"Connected to: ${serverInfo.get("name").asText()} v${serverInfo.get("version").asText()}")
      else logger.warn("serverInfo or its fields missing in result")

      val capabilities = result.get("capabilities")
      if (capabilities != null)
        logger.info(s"Server capabilities: ${capabilities}")
      else logger.warn("capabilities missing in result")
    } else if (response.has("error")) {
      val error = response.get("error")
      if (error.has("message"))
        logger.error(s"Initialize error: ${error.get("message").asText()}")
      else logger.error("Initialize error: message field missing")
    }
  }

  /**
   * List available tools
   */
  private def listTools(serverUrl: String): Unit = {
    val listRequest = Map(
      "jsonrpc" -> "2.0",
      "id" -> 2,
      "method" -> "tools/list",
      "params" -> Map[String, Any]().asJava
    ).asJava

    val response = sendJsonRpcRequest(serverUrl, listRequest.asInstanceOf[java.util.Map[String, Any]])

    if (response.has("result")) {
      val result = response.get("result")
      val tools = result.get("tools")
      if (tools != null) {
        logger.info(s"Found ${tools.size()} tools:")
        tools.elements().asScala.foreach { tool =>
          val name = if (tool.has("name")) tool.get("name").asText() else "<missing name>"
          val desc = if (tool.has("description")) tool.get("description").asText() else "<missing description>"
          logger.info(s"  - ${name}: ${desc}")
        }
      } else {
        logger.warn("tools field missing in result")
      }
    } else if (response.has("error")) {
      val error = response.get("error")
      if (error.has("message"))
        logger.error(s"List tools error: ${error.get("message").asText()}")
      else logger.error("List tools error: message field missing")
    }
  }

  /**
   * Test the echo tool with various messages
   */
  private def testEchoTool(serverUrl: String): Unit = {
    val testMessages = List("Hello, MCP!", "Testing HTTP transport", "Scala 3 rocks!")

    testMessages.zipWithIndex.foreach { case (message, index) =>
      Try {
        val callRequest = Map(
          "jsonrpc" -> "2.0",
          "id" -> (10 + index),
          "method" -> "tools/call",
          "params" -> Map(
            "name" -> "echo",
            "arguments" -> Map(
              "message" -> message
            ).asJava
          ).asJava
        ).asJava

        val response = sendJsonRpcRequest(serverUrl, callRequest.asInstanceOf[java.util.Map[String, Any]])

        if (response.has("result")) {
          val result = response.get("result")
          val content = result.get("content")

          content.elements().asScala.foreach { item =>
            if (item.get("type").asText() == "text") {
              logger.info(s"  Input: '$message'")
              logger.info(s"  Output: '${item.get("text").asText()}'")
            }
          }
        } else if (response.has("error")) {
          val error = response.get("error")
          logger.error(s"Echo tool error for message '$message': ${error.get("message").asText()}")
        }
      } match {
        case Failure(ex) =>
          logger.error(s"Failed to call echo tool with message '$message'", ex)
        case Success(_) => // Already logged
      }
    }
  }

  /**
   * Test the system_info tool
   */
  private def testSystemInfoTool(serverUrl: String): Unit = {
    Try {
      val callRequest = Map(
        "jsonrpc" -> "2.0",
        "id" -> 20,
        "method" -> "tools/call",
        "params" -> Map(
          "name" -> "system_info",
          "arguments" -> Map[String, Any]().asJava
        ).asJava
      ).asJava

      val response = sendJsonRpcRequest(serverUrl, callRequest.asInstanceOf[java.util.Map[String, Any]])

      if (response.has("result")) {
        val result = response.get("result")
        val content = result.get("content")

        content.elements().asScala.foreach { item =>
          if (item.get("type").asText() == "text") {
            logger.info(s"System Information:\n${item.get("text").asText()}")
          }
        }
      } else if (response.has("error")) {
        val error = response.get("error")
        logger.error(s"System info tool error: ${error.get("message").asText()}")
      }
    } match {
      case Failure(ex) =>
        logger.error("Failed to call system_info tool", ex)
      case Success(_) => // Already logged
    }
  }

  /**
   * Send a JSON-RPC request to the server
   */
  private def sendJsonRpcRequest(serverUrl: String, requestBody: java.util.Map[String, Any]): JsonNode = {
    val requestJson = jsonMapper.writeValueAsString(requestBody)

    val request = HttpRequest.newBuilder()
      .uri(URI.create(serverUrl))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(requestJson))
      .timeout(Duration.ofSeconds(10))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() == 200) {
      jsonMapper.readTree(response.body())
    } else {
      logger.error(s"HTTP error: ${response.statusCode()}")
      logger.error(s"Response body: ${response.body()}")
      throw new RuntimeException(s"HTTP request failed with status ${response.statusCode()}")
    }
  }
}
