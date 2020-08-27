package io.github.vigoo.zioaws.codegen

import java.io.File
import java.nio.file.Path

import cats.Traverse
import cats.instances.list._
import cats.instances.either._
import io.github.vigoo.clipp.ParameterParser
import io.github.vigoo.clipp.parsers._
import io.github.vigoo.clipp.syntax._
import io.github.vigoo.zioaws.codegen.loader.ModelId

case class Parameters(targetRoot: Path)
