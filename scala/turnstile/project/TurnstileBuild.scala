package app.dragon.turnstile

import sbt.*
import sbt.Keys.*

object TurnstileBuild {
  final val DefaultScalacOptions = Seq(
    "-deprecation",
    "-encoding", "utf-8",
    "-explaintypes",
    "-feature",
    "-language:existentials",
    "-language:experimental.macros",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked"
  )
}
