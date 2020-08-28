package io.github.vigoo.zioaws.codegen

import zio.nio.core.file.Path

case class Parameters(targetRoot: Path,
                      travisSource: Path,
                      travisTarget: Path,
                      parallelTravisJobs: Int)
