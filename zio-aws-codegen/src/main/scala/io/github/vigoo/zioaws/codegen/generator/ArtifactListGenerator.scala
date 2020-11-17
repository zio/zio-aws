package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.loader.ModelId

trait ArtifactListGenerator {
  this: HasConfig with GeneratorBase =>

  def generateArtifactList(ids: Set[ModelId], version: String): String = {
    val prefix = s"""---
                    |layout: docs
                    |title: Artifacts
                    |---
                    |
                    |# Published artifacts
                    |
                    |### HTTP client modules:
                    |```scala
                    |"io.github.vigoo" %% "zio-aws-akka-http" % "$version"
                    |"io.github.vigoo" %% "zio-aws-http4s" % "$version"
                    |"io.github.vigoo" %% "zio-aws-netty" % "$version"
                    |```
                    |
                    |### List of all the generated libraries:
                    |
                    |```scala
                    |""".stripMargin

    val clients = ids.toList
      .sortBy(_.moduleName)
      .map { id =>
        s""""io.github.vigoo" %% "zio-aws-${id.moduleName}" % "$version""""
      }
      .mkString("\n")

    val postfix = "\n```\n"

    prefix + clients + postfix
  }

}
