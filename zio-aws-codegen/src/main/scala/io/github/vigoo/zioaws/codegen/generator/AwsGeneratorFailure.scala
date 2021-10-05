package io.github.vigoo.zioaws.codegen.generator

sealed trait AwsGeneratorFailure
case class CannotFindEventStreamInShape(service: String, name: String)
    extends AwsGeneratorFailure
case class UnknownShapeReference(service: String, name: String)
    extends AwsGeneratorFailure
case class UnknownType(service: String, typ: String) extends AwsGeneratorFailure
case class InvalidPaginatedOperation(service: String, name: String)
    extends AwsGeneratorFailure
case class UnknownError(reason: Throwable) extends AwsGeneratorFailure
