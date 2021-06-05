package io.github.vigoo.zioaws.codegen

import zio.nio.core.file.Path

case class Parameters(
    targetRoot: Path,
    ciTarget: Path,
    parallelCiJobs: Int,
    separateCiJobs: Set[String],
    artifactListTarget: Path,
    version: String,
    scalaVersion: String
)
