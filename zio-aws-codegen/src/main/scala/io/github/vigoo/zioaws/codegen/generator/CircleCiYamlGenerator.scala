package io.github.vigoo.zioaws.codegen.generator

import io.circe.Json
import io.circe.syntax._
import io.github.vigoo.zioaws.codegen.loader.ModelId

trait CircleCiYamlGenerator {
  this: HasConfig with GeneratorBase =>

  def generateCircleCiYaml(
      ids: Set[ModelId],
      parallelJobs: Int,
      source: String
  ): String = {
    val sortedProjectNames =
      ids.map(id => s"zio-aws-${id.moduleName}").toList.sorted
    val grouped = sortedProjectNames.grouped(
      Math.ceil(ids.size.toDouble / parallelJobs.toDouble).toInt
    )
    val envDefs = grouped
      .map(group =>
        s""""${group.map(name => s"$name/publishSigned").mkString(" ")}""""
      )
      .toVector

    source.replace("COMMANDS", s"[${envDefs.mkString(",")}]")
  }
}
