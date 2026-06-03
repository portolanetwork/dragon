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

package app.dragon.turnstile.mcp_client

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper, TypeRef}

import java.io.IOException

/**
 * McpJsonMapper wrapper that normalizes JSON Schema `additionalProperties` before
 * deserialization.
 *
 * The MCP SDK's McpSchema.JsonSchema record types additionalProperties as Boolean, but
 * JSON Schema (draft-07+) allows it to be either a boolean or a schema object. When a
 * downstream server returns an object for additionalProperties, Jackson throws a
 * deserialization error. This wrapper detects that case and removes the field before
 * passing the JSON to the SDK's default mapper.
 */
class PermissiveJsonSchemaMcpJsonMapper extends McpJsonMapper {

  private val delegate = McpJsonDefaults.getMapper()
  // Uses the Jackson 2 ObjectMapper already on the classpath (via Pekko/Circe)
  private val preProcessor = new ObjectMapper()

  private def normalizeJson(json: String): String =
    try {
      val tree = preProcessor.readTree(json)
      stripAdditionalPropertiesObjects(tree)
      preProcessor.writeValueAsString(tree)
    } catch {
      case _: Exception => json
    }

  private def normalizeJson(bytes: Array[Byte]): Array[Byte] =
    try {
      val tree = preProcessor.readTree(bytes)
      stripAdditionalPropertiesObjects(tree)
      preProcessor.writeValueAsBytes(tree)
    } catch {
      case _: Exception => bytes
    }

  // Recursively removes any `additionalProperties` node that is an object.
  // JSON Schema allows both boolean and object forms; the SDK only handles boolean.
  // Removing the field (rather than converting to true/false) is the safest no-op.
  private def stripAdditionalPropertiesObjects(node: JsonNode): Unit = {
    node match {
      case obj: ObjectNode =>
        Option(obj.get("additionalProperties")) match {
          case Some(ap) if ap.isObject => obj.remove("additionalProperties")
          case _ =>
        }
        obj.properties().forEach(entry => stripAdditionalPropertiesObjects(entry.getValue))
      case arr: ArrayNode =>
        arr.forEach(child => stripAdditionalPropertiesObjects(child))
      case _ =>
    }
  }

  @throws[IOException]
  override def readValue[T](content: String, tpe: Class[T]): T =
    delegate.readValue(normalizeJson(content), tpe)

  @throws[IOException]
  override def readValue[T](content: Array[Byte], tpe: Class[T]): T =
    delegate.readValue(normalizeJson(content), tpe)

  @throws[IOException]
  override def readValue[T](content: String, tpe: TypeRef[T]): T =
    delegate.readValue(normalizeJson(content), tpe)

  @throws[IOException]
  override def readValue[T](content: Array[Byte], tpe: TypeRef[T]): T =
    delegate.readValue(normalizeJson(content), tpe)

  override def convertValue[T](fromValue: Object, tpe: Class[T]): T =
    delegate.convertValue(fromValue, tpe)

  override def convertValue[T](fromValue: Object, tpe: TypeRef[T]): T =
    delegate.convertValue(fromValue, tpe)

  @throws[IOException]
  override def writeValueAsString(value: Object): String =
    delegate.writeValueAsString(value)

  @throws[IOException]
  override def writeValueAsBytes(value: Object): Array[Byte] =
    delegate.writeValueAsBytes(value)
}
