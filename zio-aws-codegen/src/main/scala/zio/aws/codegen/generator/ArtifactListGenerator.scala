package zio.aws.codegen.generator

import zio.aws.codegen.loader.ModuleId

trait ArtifactListGenerator {
  this: HasConfig with GeneratorBase =>

  def generateArtifactList(ids: Set[ModuleId], version: String): String = {
    val prefix = s"""---
                    |id: artifacts
                    |title: Artifacts
                    |---
                    |
                    |# Published artifacts
                    |
                    |## Core module
                    |
                    |```scala
                    |"dev.zio" %% "zio-aws-core" % "$version"
                    |```
                    |
                    |## HTTP client modules:
                    |
                    |```scala
                    |"dev.zio" %% "zio-aws-akka-http" % "$version"
                    |"dev.zio" %% "zio-aws-http4s" % "$version"
                    |"dev.zio" %% "zio-aws-netty" % "$version"
                    |```
                    |
                    |## List of all the generated libraries:
                    |
                    |```scala
                    |""".stripMargin

    val clients = ids.toList
      .sortBy(_.moduleName)
      .map { id =>
        s""""dev.zio" %% "zio-aws-${id.moduleName}" % "$version""""
      }
      .mkString("\n")

    val postfix = "\n```\n"

    prefix + clients + postfix
  }

}
