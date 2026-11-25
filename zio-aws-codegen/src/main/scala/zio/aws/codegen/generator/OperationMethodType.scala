package zio.aws.codegen.generator

/** The kind of method that the generator emits for an operation. [[OperationCollector]] picks it from the input and output shapes of the operation. */
sealed trait OperationMethodType

/** The cases of [[OperationMethodType]]. */
object OperationMethodType {
  /** An operation with no input and no output. */
  case object UnitToUnit extends OperationMethodType

  /** An operation with no input and an output. */
  case object UnitToResponse extends OperationMethodType

  /** An operation with an input and no output. */
  case object RequestToUnit extends OperationMethodType

  /** An operation with an input and an output, none of them streaming. `pagination` holds the definition of the paginated member when the operation is paginated. */
  case class RequestResponse(pagination: Option[PaginationDefinition])
      extends OperationMethodType

  /** An operation with a streaming input and an output. */
  case object StreamedInput extends OperationMethodType

  /** An operation with a streaming input and no output. */
  case object StreamedInputToUnit extends OperationMethodType

  /** An operation with a streaming output. */
  case object StreamedOutput extends OperationMethodType

  /** An operation with a streaming input and a streaming output. */
  case object StreamedInputOutput extends OperationMethodType

  /** An operation whose input is an event stream. */
  case object EventStreamInput extends OperationMethodType

  /** An operation whose output is an event stream. */
  case object EventStreamOutput extends OperationMethodType

  /** An operation whose input and output are both event streams. */
  case object EventStreamInputOutput extends OperationMethodType
}
