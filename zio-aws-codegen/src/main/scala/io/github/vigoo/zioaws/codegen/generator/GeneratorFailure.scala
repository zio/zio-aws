package io.github.vigoo.zioaws.codegen.generator

sealed trait GeneratorFailure
case class FailedToCreateDirectories(reason: Throwable) extends GeneratorFailure
case class FailedToWriteFile(reason: Throwable) extends GeneratorFailure
case class FailedToCopy(reason: Throwable) extends GeneratorFailure
case class FailedToDelete(reason: Throwable) extends GeneratorFailure
case class CannotFindEventStreamInShape(name: String) extends GeneratorFailure