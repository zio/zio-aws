package io.github.vigoo.zioaws.codegen.generator

import io.circe.Json
import io.circe.syntax._
import io.github.vigoo.zioaws.codegen.loader.ModelId

trait CircleCiYamlGenerator {
  this: HasConfig with GeneratorBase =>

  def generateCircleCiYaml(
      ids: Set[ModelId],
      parallelJobs: Int,
      separateJobs: Set[String],
      source: String
  ): String = {
    val sortedProjectNames =
      ids
        .map(id => s"zio-aws-${id.moduleName}")
        .toList
        .sorted

    val (separateProjectNames, filteredProjectNames) =
      sortedProjectNames.partition(separateJobs.contains)

    val grouped = filteredProjectNames
      .grouped(
        Math.ceil(ids.size.toDouble / parallelJobs.toDouble).toInt
      )
      .toList ++ separateProjectNames.map(List(_))

    val compile = grouped
      .map(group =>
        s""""${group.map(name => s"$name/compile").mkString(" ")}""""
      )
      .toVector
    val publish = grouped
      .map(group =>
        s""""${group.map(name => s"$name/publishSigned").mkString(" ")}""""
      )
      .toVector

    source
      .replace("COMPILE_COMMANDS", s"[${compile.mkString(",")}]")
      .replace("PUBLISH_COMMANDS", s"[${publish.mkString(",")}]")
  }
}
