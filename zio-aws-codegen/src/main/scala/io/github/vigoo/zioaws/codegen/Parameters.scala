package io.github.vigoo.zioaws.codegen

import java.io.File
import java.nio.file.Path

import io.github.vigoo.clipp.parsers._
import io.github.vigoo.clipp.syntax._

case class Parameters(targetRoot: Path,
                      sourceRoot: Path,
                      version: String,
                      zioVersion: String,
                      zioInteropReactiveStreamsVersion: String)

object Parameters {
  val spec = for {
    _ <- metadata("zio-aws-codegen", "Code generator of the zio-aws modules")
    targetRoot <- namedParameter[File]("Target root directory", "DIR", "target-root")
    sourceRoot <- namedParameter[File]("Source root directory", "DIR", "source-root")
    version <- namedParameter[String]("Version", "VER", "version")
    zioVersion <- namedParameter[String]("Version of ZIO", "VER", "zio-version")
    zioInteropReactiveStreamsVersion <- namedParameter[String]("Version of zio-interop-reactivestreams", "VER", "zio-rs-version")
  } yield Parameters(targetRoot.toPath, sourceRoot.toPath, version, zioVersion, zioInteropReactiveStreamsVersion)

}
