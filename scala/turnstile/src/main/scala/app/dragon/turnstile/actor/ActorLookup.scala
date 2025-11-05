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

package app.dragon.turnstile.actor

import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Actor lookup utilities for accessing sharded actors.
 *
 * This object provides convenient methods to obtain EntityRef instances for
 * cluster-sharded actors. Using EntityRef provides location transparency - you
 * can send messages to actors without knowing which node they're running on.
 *
 * Supported Actor Types:
 * - McpServerActor: User-scoped MCP server instances
 * - McpClientActor: Connections to downstream MCP servers
 * - McpSessionMapActor: Session routing and management
 *
 * All lookups use ClusterSharding to:
 * 1. Find existing actor instances or create new ones
 * 2. Route messages to the correct node in the cluster
 * 3. Maintain actor lifecycle and persistence
 *
 * Usage:
 * {{{
 * implicit val sharding: ClusterSharding = ClusterSharding(system)
 *
 * // Get a server actor by ID
 * val serverActor = ActorLookup.getMcpServerActor("user123-session456")
 * serverActor ! McpServerActor.McpGetRequest(request, replyTo)
 *
 * // Get a client actor by user and server UUID
 * val clientActor = ActorLookup.getMcpClientActor("user123", "server-uuid-789")
 * clientActor ! McpClientActor.McpListTools(replyTo)
 * }}}
 */
object ActorLookup {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  /**
   * Get a reference to an MCP server actor by its entity ID.
   *
   * @param mcpServerActorId The entity ID (format: "userId-actorId")
   * @param sharding Cluster sharding instance
   * @return EntityRef for the MCP server actor
   */
  def getMcpServerActor(
    mcpServerActorId: String
  )(implicit sharding: ClusterSharding): EntityRef[McpServerActor.Message] =
    sharding.entityRefFor(McpServerActor.TypeKey, mcpServerActorId)

  /**
   * Get a reference to an MCP client actor by its entity ID.
   *
   * @param mcpClientActorId The entity ID (format: "userId-clientId")
   * @param sharding Cluster sharding instance
   * @return EntityRef for the MCP client actor
   */
  def getMcpClientActor(
    mcpClientActorId: String
  )(implicit sharding: ClusterSharding): EntityRef[McpClientActor.Message] =
    sharding.entityRefFor(McpClientActor.TypeKey, mcpClientActorId)

  /**
   * Get a reference to an MCP client actor by user and server UUID.
   *
   * This is a convenience method that constructs the entity ID from components.
   *
   * @param userId The user identifier
   * @param mcpServerUuid The downstream MCP server UUID
   * @param sharding Cluster sharding instance
   * @return EntityRef for the MCP client actor
   */
  def getMcpClientActor(
    userId: String,
    mcpServerUuid: String
  )(implicit sharding: ClusterSharding): EntityRef[McpClientActor.Message] =
    getMcpClientActor(McpClientActorId.getEntityId(userId, mcpServerUuid))

  /**
   * Get a reference to an MCP session map actor by user ID.
   *
   * @param userId The user identifier
   * @param sharding Cluster sharding instance
   * @return EntityRef for the MCP session map actor
   */
  def getMcpSessionMapActor(
    userId: String
  )(implicit sharding: ClusterSharding): EntityRef[McpSessionMapActor.Message] =
    sharding.entityRefFor(McpSessionMapActor.TypeKey, userId)
}