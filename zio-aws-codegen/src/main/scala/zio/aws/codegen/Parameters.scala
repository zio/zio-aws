package zio.aws.codegen

import zio.nio.file.Path

case class Parameters(
    targetRoot: Path,
    ciTarget: Path,
    parallelCiJobs: Int,
    separateCiJobs: Set[String],
    artifactListTarget: Path,
    version: String,
    scalaVersion: String
)
