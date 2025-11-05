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

package app.dragon.turnstile.utils

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.google.protobuf.struct.{Struct, Value}
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Utility object for JSON <-> protobuf Struct conversions.
 *
 * Provides:
 * - stringToStruct: JSON string -> protobuf Struct
 * - structToString: protobuf Struct -> JSON string
 */
object JsonUtils {
  private val logger: Logger = LoggerFactory.getLogger(JsonUtils.getClass)
  private val objectMapper = new ObjectMapper()

  /**
   * Convert a JSON string to a protobuf Struct.
   *
   * @param jsonString The JSON string to convert
   * @return Try[Struct] containing the converted Struct or a Failure
   */
  def stringToStruct(
    jsonString: String
  ): Try[Struct] = Try {
    val jsonNode = objectMapper.readTree(jsonString)
    jsonNodeToStruct(jsonNode)
  }

  /**
   * Convert a protobuf Struct to a JSON string.
   *
   * @param struct The Struct to convert
   * @return Try[String] containing the JSON string or a Failure
   */
  def structToString(
    struct: Struct
  ): Try[String] = Try {
    val jsonNode = structToJsonNode(struct, objectMapper)
    objectMapper.writeValueAsString(jsonNode)
  }

  /**
   * Convert a Java Map to a protobuf Struct.
   *
   * @param map The Java Map to convert
   * @return Try[Struct] containing the converted Struct or a Failure
   */
  def mapToStruct(
    map: java.util.Map[String, Object]
  ): Try[Struct] = Try {
    val jsonString = objectMapper.writeValueAsString(map)
    stringToStruct(jsonString).get
  }

  /**
   * Parse a JSON schema string into a McpSchema.JsonSchema.
   *
   * @param json The JSON schema as a string
   * @return Try[McpSchema.JsonSchema]
   */
  def parseJsonSchema(
    json: String)
  : Try[McpSchema.JsonSchema] = Try {
    val node = objectMapper.readTree(json)
    val schemaType = Option(node.get("type")).map(_.asText()).getOrElse("object")
    val properties = Option(node.get("properties")).map { propsNode =>
      import scala.jdk.CollectionConverters._
      propsNode.properties().asScala.map { entry =>
        entry.getKey -> objectMapper.convertValue(entry.getValue, classOf[java.util.Map[String, Object]]).asInstanceOf[Object]
      }.toMap.asJava
    }.getOrElse(new java.util.HashMap[String, Object]())
    val required = Option(node.get("required")).map(_.elements().asScala.map(_.asText()).toList.asJava).getOrElse(new java.util.ArrayList[String]())
    val defs = Option(node.get("defs")).map { defsNode =>
      import scala.jdk.CollectionConverters._
      defsNode.properties().asScala.map { entry =>
        entry.getKey -> objectMapper.convertValue(entry.getValue, classOf[java.util.Map[String, Object]]).asInstanceOf[Object]
      }.toMap.asJava
    }.getOrElse(new java.util.HashMap[String, Object]())
    val definitions = Option(node.get("definitions")).map { defsNode =>
      import scala.jdk.CollectionConverters._
      defsNode.properties().asScala.map { entry =>
        entry.getKey -> objectMapper.convertValue(entry.getValue, classOf[java.util.Map[String, Object]]).asInstanceOf[Object]
      }.toMap.asJava
    }.getOrElse(new java.util.HashMap[String, Object]())
    new McpSchema.JsonSchema(schemaType, properties, required, null, defs, definitions)
  }

  // --- Private helpers ---

  private def jsonNodeToStruct(
    node: JsonNode
  ): Struct = {
    if (!node.isObject) {
      throw new IllegalArgumentException("JsonNode must be an object to convert to Struct")
    }
    val fields = node.properties().asScala.map { entry =>
      val key = entry.getKey
      val value = jsonNodeToValue(entry.getValue)
      key -> value
    }.toMap
    Struct(fields = fields)
  }

  private def structToJsonNode(
    struct: Struct, 
    mapper: ObjectMapper
  ): JsonNode = {
    val objectNode = mapper.createObjectNode()
    struct.fields.foreach { case (key, value) =>
      val jsonValue = valueToJsonNode(value, mapper)
      objectNode.set[JsonNode](key, jsonValue)
    }
    objectNode
  }

  private def jsonNodeToValue(
    node: JsonNode
  ): Value = {
    node match {
      case n if n.isNull => Value(Value.Kind.NullValue(com.google.protobuf.struct.NullValue.NULL_VALUE))
      case n if n.isBoolean => Value(Value.Kind.BoolValue(n.asBoolean()))
      case n if n.isNumber => Value(Value.Kind.NumberValue(n.asDouble()))
      case n if n.isTextual => Value(Value.Kind.StringValue(n.asText()))
      case n if n.isArray =>
        val values = n.elements().asScala.map(jsonNodeToValue).toSeq
        Value(Value.Kind.ListValue(com.google.protobuf.struct.ListValue(values)))
      case n if n.isObject =>
        Value(Value.Kind.StructValue(jsonNodeToStruct(n)))
      case _ =>
        Value(Value.Kind.NullValue(com.google.protobuf.struct.NullValue.NULL_VALUE))
    }
  }

  private def valueToJsonNode(
    value: Value, 
    mapper: ObjectMapper
  ): JsonNode = {
    value.kind match {
      case Value.Kind.NullValue(_) =>
        mapper.nullNode()
      case Value.Kind.BoolValue(b) =>
        mapper.getNodeFactory.booleanNode(b)
      case Value.Kind.NumberValue(n) =>
        mapper.getNodeFactory.numberNode(n)
      case Value.Kind.StringValue(s) =>
        mapper.getNodeFactory.textNode(s)
      case Value.Kind.ListValue(list) =>
        val arrayNode = mapper.createArrayNode()
        list.values.foreach { v =>
          arrayNode.add(valueToJsonNode(v, mapper))
        }
        arrayNode
      case Value.Kind.StructValue(struct) =>
        structToJsonNode(struct, mapper)
      case Value.Kind.Empty =>
        mapper.nullNode()
    }
  }
}
