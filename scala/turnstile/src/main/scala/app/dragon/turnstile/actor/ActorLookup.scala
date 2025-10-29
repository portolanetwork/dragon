package app.dragon.turnstile.actor

import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import org.slf4j.{Logger, LoggerFactory}

object ActorLookup {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)
  
  def getMcpActor(
    mcpActorId: String
  )(implicit sharding: ClusterSharding): EntityRef[McpActor.Message] =
    sharding.entityRefFor(McpActor.TypeKey, mcpActorId)

}