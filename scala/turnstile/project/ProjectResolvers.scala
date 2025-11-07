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

import sbt.*

object ProjectResolvers {
  val publicAkkaRepo = "Akka library repository".at("https://repo.org.apache.pekko.io/maven")
  val mavenCentral = "Maven Central" at "https://repo1.maven.org/maven2/"

  // Include Maven Central so common OSS artifacts (circe, etc.) are resolvable.
  val resolvers = Seq(
    mavenCentral,
    publicAkkaRepo
  )
}
