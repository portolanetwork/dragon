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

import io.modelcontextprotocol.spec.McpSchema

import scala.jdk.CollectionConverters.*

/**
 * Utility methods for MCP tool implementations.
 *
 * This object provides common helper methods for creating schemas, results,
 * and extracting arguments from MCP tool requests.
 */
object McpUtils {

  /**
   * Helper to create JSON schema for object types
   *
   * @param properties Map of property name to property definition (type, description, etc.)
   * @param required Sequence of required property names
   * @return JsonSchema instance
   */
  def createObjectSchema(
    properties: Map[String, Map[String, String]] = Map.empty,
    required: Seq[String] = Seq.empty
  ): McpSchema.JsonSchema = {
    val javaProperties = properties.map { case (key, value) =>
      key -> value.asJava.asInstanceOf[Object]
    }.asJava

    new McpSchema.JsonSchema(
      "object",
      javaProperties,
      required.asJava,
      null, // additionalProperties
      null, // defs
      null  // definitions
    )
  }

  /**
   * Helper to create a text-based tool result
   *
   * @param text The text content to return
   * @param isError Whether this is an error result
   * @return CallToolResult instance
   */
  def createTextResult(
    text: String, 
    isError: Boolean = false
  ): McpSchema.CallToolResult = {
    val content: java.util.List[McpSchema.Content] = List(
      new McpSchema.TextContent(text)
    ).asJava.asInstanceOf[java.util.List[McpSchema.Content]]

    McpSchema.CallToolResult.builder()
      .content(content)
      .isError(isError)
      .build()
  }

  /**
   * Helper to create a tool schema builder with common settings
   *
   * @param name Tool name
   * @param description Tool description
   * @return Tool.Builder instance ready to have inputSchema set and build() called
   */
  def createToolSchemaBuilder(
    name: String, 
    description: String
  ): McpSchema.Tool.Builder = {
    McpSchema.Tool.builder()
      .name(name)
      .description(description)
  }

  /**
   * Helper to extract a string argument from the request
   *
   * @param request The CallToolRequest
   * @param argName The argument name to extract
   * @param default Default value if argument is missing
   * @return The argument value or default
   */
  def getStringArg(
    request: McpSchema.CallToolRequest,
    argName: String,
    default: String = ""
  ): String = {
    Option(request.arguments())
      .flatMap(args => Option(args.get(argName)))
      .map(_.toString)
      .getOrElse(default)
  }

  /**
   * Helper to extract an integer argument from the request
   *
   * @param request The CallToolRequest
   * @param argName The argument name to extract
   * @param default Default value if argument is missing or invalid
   * @return The argument value or default
   */
  def getIntArg(
    request: McpSchema.CallToolRequest,
    argName: String,
    default: Int = 0
  ): Int = {
    Option(request.arguments())
      .flatMap(args => Option(args.get(argName)))
      .flatMap(v => scala.util.Try(v.toString.toInt).toOption)
      .getOrElse(default)
  }

  /**
   * Helper to extract a boolean argument from the request
   *
   * @param request The CallToolRequest
   * @param argName The argument name to extract
   * @param default Default value if argument is missing or invalid
   * @return The argument value or default
   */
  def getBooleanArg(
    request: McpSchema.CallToolRequest,
    argName: String,
    default: Boolean = false
  ): Boolean = {
    Option(request.arguments())
      .flatMap(args => Option(args.get(argName)))
      .flatMap(v => scala.util.Try(v.toString.toBoolean).toOption)
      .getOrElse(default)
  }
}
