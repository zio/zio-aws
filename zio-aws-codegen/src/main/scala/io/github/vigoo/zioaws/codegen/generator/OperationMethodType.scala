package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.generator.TypeMapping.toType
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import zio.console.Console
import zio.{ZIO, console}

import scala.jdk.CollectionConverters._

sealed trait OperationMethodType

case class RequestResponse(pagination: Option[PaginationDefinition]) extends OperationMethodType

case object StreamedInput extends OperationMethodType

case object StreamedOutput extends OperationMethodType

case object StreamedInputOutput extends OperationMethodType

case object EventStreamInput extends OperationMethodType

case object EventStreamOutput extends OperationMethodType

case object EventStreamInputOutput extends OperationMethodType
