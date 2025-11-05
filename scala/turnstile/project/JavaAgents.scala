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
