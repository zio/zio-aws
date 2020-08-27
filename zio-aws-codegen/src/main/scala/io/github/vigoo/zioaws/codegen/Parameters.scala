package io.github.vigoo.zioaws.codegen

import java.nio.file.Path

case class Parameters(targetRoot: Path,
                      travisSource: Path,
                      travisTarget: Path,
                      parallelTravisJobs: Int)
