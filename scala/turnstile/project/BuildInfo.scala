package app.dragon.turnstile

import sbtbuildinfo.BuildInfoKey
import sys.process._

object BuildInfo {

  private val gitCommit: String = {
    sys.env.getOrElse("GIT_COMMIT", "git rev-parse HEAD".!!).trim
  }

  final val customBuildInfoKeys = Seq[BuildInfoKey]("gitCommit" -> gitCommit)

  final val buildInfoPackage = "app.dragon.turnstile.build"
}
