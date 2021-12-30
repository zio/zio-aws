package zio.aws.codegen.generator

sealed trait OperationMethodType

object OperationMethodType {
  case object UnitToUnit extends OperationMethodType

  case object UnitToResponse extends OperationMethodType

  case object RequestToUnit extends OperationMethodType

  case class RequestResponse(pagination: Option[PaginationDefinition])
      extends OperationMethodType

  case object StreamedInput extends OperationMethodType

  case object StreamedInputToUnit extends OperationMethodType

  case object StreamedOutput extends OperationMethodType

  case object StreamedInputOutput extends OperationMethodType

  case object EventStreamInput extends OperationMethodType

  case object EventStreamOutput extends OperationMethodType

  case object EventStreamInputOutput extends OperationMethodType
}
