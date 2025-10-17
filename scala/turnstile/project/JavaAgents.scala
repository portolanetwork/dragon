import com.lightbend.sbt.javaagent.JavaAgent
import sbt._


object JavaAgents {

  val jmxPrometheusAgentV = "0.17.2"
  val kanelaAgentV = "1.0.16"

  val jmxPrometheusAgent = JavaAgent("io.prometheus.jmx" % "jmx_prometheus_javaagent" % jmxPrometheusAgentV, arguments = "9404:/opt/turnstile/conf/jmx-prometheus.yaml")
  val kanelaAgent = JavaAgent("io.kamon" % "kanela-agent" % kanelaAgentV)

  val javaAgents = Seq(
    jmxPrometheusAgent,
    kanelaAgent)
}
