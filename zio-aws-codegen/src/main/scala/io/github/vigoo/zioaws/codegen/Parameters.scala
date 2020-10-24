package io.github.vigoo.zioaws.codegen

import zio.nio.core.file.Path

case class Parameters(
    targetRoot: Path,
    circleCiSource: Path,
    circleCiTarget: Path,
    parallelCircleCiJobs: Int
)
