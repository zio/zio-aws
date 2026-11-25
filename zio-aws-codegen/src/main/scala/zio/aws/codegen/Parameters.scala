package zio.aws.codegen

import zio.nio.file.Path

/** The settings that the plugin passes to the generator.
  *
  * @param targetRoot
  *   the directory that the generated sources are written under
  * @param artifactListTarget
  *   the file that the artifact list is written to
  * @param scalaVersion
  *   the Scala version of the project being generated
  */
case class Parameters(
    targetRoot: Path,
    parallelCiJobs: Int,
    separateCiJobs: Set[String],
    artifactListTarget: Path,
    version: String,
    scalaVersion: String
)
