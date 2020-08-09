package io.github.vigoo.zioaws.codegen.generator

sealed trait GeneratorError
case class FailedToCreateDirectories(reason: Throwable) extends GeneratorError
case class FailedToWriteFile(reason: Throwable) extends GeneratorError
case class FailedToCopy(reason: Throwable) extends GeneratorError
case class FailedToDelete(reason: Throwable) extends GeneratorError
case class CannotFindEventStreamInShape(name: String) extends GeneratorError