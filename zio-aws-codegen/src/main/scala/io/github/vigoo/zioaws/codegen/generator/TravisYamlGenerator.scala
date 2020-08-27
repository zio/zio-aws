package io.github.vigoo.zioaws.codegen.generator

import io.circe.Json
import io.circe.syntax._
import io.github.vigoo.zioaws.codegen.loader.ModelId

trait TravisYamlGenerator {
  this: HasConfig with GeneratorBase =>


  def generateTravisYaml(ids: Set[ModelId], parallelJobs: Int, source: Json): Json = {
    val sortedProjectNames = ids.map(id => s"zio-aws-${id.moduleName}").toList.sorted
    val grouped = sortedProjectNames.grouped(Math.ceil(ids.size.toDouble / parallelJobs.toDouble).toInt)
    val envDefs = grouped.map(group =>
    s"COMMANDS=clean ${group.map(name => s"$name/compile").mkString(" ")}"
    ).toList

    source.deepMerge(Json.obj(
      "env" := Json.fromValues(
        envDefs.map(Json.fromString)
      )
    ))
  }
}
