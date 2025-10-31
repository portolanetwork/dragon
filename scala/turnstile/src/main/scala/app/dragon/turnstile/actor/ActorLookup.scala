package app.dragon.turnstile.actor

import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import org.slf4j.{Logger, LoggerFactory}

object ActorLookup {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  def getMcpServerActor(
    mcpServerActorId: String
  )(implicit sharding: ClusterSharding): EntityRef[McpServerActor.Message] =
    sharding.entityRefFor(McpServerActor.TypeKey, mcpServerActorId)

  def getMcpClientActor(  
    mcpClientActorId: String
  )(implicit sharding: ClusterSharding): EntityRef[McpClientActor.Message] =
    sharding.entityRefFor(McpClientActor.TypeKey, mcpClientActorId)

  def getMcpClientActor(
    userId: String,
    mcpServerUuid: String
  )(implicit sharding: ClusterSharding): EntityRef[McpClientActor.Message] =
    getMcpClientActor(McpClientActorId.getEntityId(userId, mcpServerUuid))
  
  
}