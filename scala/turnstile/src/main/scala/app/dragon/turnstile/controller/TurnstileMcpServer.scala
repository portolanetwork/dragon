package app.dragon.turnstile.controller

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.{entity, *}
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * MCP Server implementation for Turnstile.
 * Provides tools and resources via Model Context Protocol over HTTP.
 *
 * Implements HTTP transport for internet accessibility:
 * - Single /mcp endpoint supporting POST for JSON-RPC
 * - GET for server info
 * - Stateless request handling
 *
 * Currently implements:
 * - echo: Simple message echo for testing
 * - system_info: Return server system information
 */
class TurnstileMcpServer(config: Config)(implicit system: ActorSystem[?]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[TurnstileMcpServer])
  private implicit val ec: ExecutionContext = system.executionContext

  private val serverName = config.getString("name")
  private val serverVersion = config.getString("version")
  private val host = config.getString("host")
  private val port = config.getInt("port")

  private val jsonMapper = new ObjectMapper()
  private val bindingRef: AtomicReference[Option[Http.ServerBinding]] = new AtomicReference(None)

  /**
   * Handle MCP JSON-RPC requests
   */
  private def handleMcpRequest(jsonRequest: String): String = {
    try {
      val request = jsonMapper.readValue(jsonRequest, classOf[JsonNode])
      val method = request.get("method").asText()
      val id = if (request.has("id")) request.get("id") else null

      method match {
        case "initialize" =>
          handleInitialize(id)

        case "tools/list" =>
          handleToolsList(id)

        case "tools/call" =>
          val params = request.get("params")
          val toolName = params.get("name").asText()
          val arguments = if (params.has("arguments")) params.get("arguments") else jsonMapper.createObjectNode()
          handleToolCall(id, toolName, arguments)

        case _ =>
          createErrorResponse(id, -32601, s"Method not found: $method")
      }
    } catch {
      case e: Exception =>
        logger.error("Error handling MCP request", e)
        createErrorResponse(null, -32700, s"Parse error: ${e.getMessage}")
    }
  }

  private def handleInitialize(id: JsonNode): String = {
    val response = Map(
      "jsonrpc" -> "2.0",
      "id" -> (if (id != null) id.asText() else "1"),
      "result" -> Map(
        "protocolVersion" -> "2024-11-05",
        "capabilities" -> Map(
          "tools" -> Map[String, Any]()
        ),
        "serverInfo" -> Map(
          "name" -> serverName,
          "version" -> serverVersion
        )
      ).asJava
    ).asJava

    jsonMapper.writeValueAsString(response)
  }

  private def handleToolsList(id: JsonNode): String = {
    val tools = List(
      Map(
        "name" -> "echo",
        "description" -> "Echoes back the provided message",
        "inputSchema" -> Map(
          "type" -> "object",
          "properties" -> Map(
            "message" -> Map(
              "type" -> "string",
              "description" -> "The message to echo back"
            ).asJava
          ).asJava,
          "required" -> List("message").asJava
        ).asJava
      ).asJava,
      Map(
        "name" -> "system_info",
        "description" -> "Returns system information about the Turnstile server",
        "inputSchema" -> Map(
          "type" -> "object",
          "properties" -> Map[String, Any]().asJava
        ).asJava
      ).asJava
    )

    val response = Map(
      "jsonrpc" -> "2.0",
      "id" -> (if (id != null) id.asText() else "1"),
      "result" -> Map(
        "tools" -> tools.asJava
      ).asJava
    ).asJava

    jsonMapper.writeValueAsString(response)
  }

  private def handleToolCall(id: JsonNode, toolName: String, arguments: JsonNode): String = {
    val result = toolName match {
      case "echo" =>
        handleEchoTool(arguments)
      case "system_info" =>
        handleSystemInfoTool()
      case _ =>
        return createErrorResponse(id, -32602, s"Unknown tool: $toolName")
    }

    val response = Map(
      "jsonrpc" -> "2.0",
      "id" -> (if (id != null) id.asText() else "1"),
      "result" -> result.asJava
    ).asJava

    jsonMapper.writeValueAsString(response)
  }

  private def handleEchoTool(arguments: JsonNode): Map[String, Any] = {
    val message = if (arguments.has("message")) arguments.get("message").asText() else ""
    logger.debug(s"Echo tool called with message: $message")

    Map(
      "content" -> List(
        Map(
          "type" -> "text",
          "text" -> s"Echo: $message"
        ).asJava
      ).asJava
    )
  }

  private def handleSystemInfoTool(): Map[String, Any] = {
    val runtime = Runtime.getRuntime
    val totalMemory = runtime.totalMemory()
    val freeMemory = runtime.freeMemory()
    val usedMemory = totalMemory - freeMemory
    val maxMemory = runtime.maxMemory()

    val info = s"""System Information:
                  |Server: $serverName v$serverVersion
                  |Java Version: ${System.getProperty("java.version")}
                  |Java Vendor: ${System.getProperty("java.vendor")}
                  |OS Name: ${System.getProperty("os.name")}
                  |OS Architecture: ${System.getProperty("os.arch")}
                  |OS Version: ${System.getProperty("os.version")}
                  |Available Processors: ${runtime.availableProcessors()}
                  |Total Memory: ${totalMemory / 1024 / 1024} MB
                  |Used Memory: ${usedMemory / 1024 / 1024} MB
                  |Free Memory: ${freeMemory / 1024 / 1024} MB
                  |Max Memory: ${maxMemory / 1024 / 1024} MB
                  |""".stripMargin

    logger.debug("System info tool called")

    Map(
      "content" -> List(
        Map(
          "type" -> "text",
          "text" -> info
        ).asJava
      ).asJava
    )
  }

  private def createErrorResponse(id: JsonNode, code: Int, message: String): String = {
    val response = Map(
      "jsonrpc" -> "2.0",
      "id" -> (if (id != null) id.asText() else null),
      "error" -> Map(
        "code" -> code,
        "message" -> message
      ).asJava
    ).asJava

    jsonMapper.writeValueAsString(response)
  }

  /**
   * Create HTTP route for MCP endpoint
   */
  private def createMcpRoute(): Route = {
    path("mcp") {
      post {
        entity(as[String]) { requestBody =>
          complete {
            val responseJson = handleMcpRequest(requestBody)
            HttpResponse(
              status = StatusCodes.OK,
              entity = HttpEntity(ContentTypes.`application/json`, responseJson)
            )
          }
        }
      } ~
      get {
        complete {
          HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              s"""{"server":"$serverName","version":"$serverVersion","transport":"http","endpoint":"http://$host:$port/mcp"}"""
            )
          )
        }
      }
    }
  }

  /**
   * Start the MCP HTTP server
   */
  def start(): Unit = {
    logger.info(s"Starting MCP Server: $serverName v$serverVersion")
    logger.info(s"MCP Server will be available via HTTP at http://$host:$port/mcp")

    val route = createMcpRoute()
    val binding = Http().newServerAt(host, port).bind(route)

    binding.onComplete {
      case Success(b) =>
        bindingRef.set(Some(b))
        logger.info(s"MCP Server bound to http://$host:$port/mcp")
        logger.info("Available tools: echo, system_info")
      case Failure(ex) =>
        logger.error(s"Failed to bind MCP Server to $host:$port", ex)
    }
  }

  /**
   * Stop the MCP HTTP server
   */
  def stop(): Future[Unit] = {
    logger.info("Stopping MCP Server")
    bindingRef.get() match {
      case Some(binding) =>
        binding.unbind().map { _ =>
          bindingRef.set(None)
          logger.info("MCP Server stopped")
        }
      case None =>
        Future.successful(())
    }
  }
}

object TurnstileMcpServer {
  def apply(config: Config)(implicit system: ActorSystem[?]): TurnstileMcpServer = {
    new TurnstileMcpServer(config)
  }
}
