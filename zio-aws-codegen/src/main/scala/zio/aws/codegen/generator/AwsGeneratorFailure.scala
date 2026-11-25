package zio.aws.codegen.generator

/** The failures that the generator reports. */
sealed trait AwsGeneratorFailure

/** No event stream shape was found in the shape `name` of `service`. */
case class CannotFindEventStreamInShape(service: String, name: String)
    extends AwsGeneratorFailure

/** The shape `name` is not in the model map of `service`. */
case class UnknownShapeReference(service: String, name: String)
    extends AwsGeneratorFailure

/** The shape type `typ` of `service` is not one that the generator knows. */
case class UnknownType(service: String, typ: String) extends AwsGeneratorFailure

/** The paginated operation `name` of `service` has no response member that the
  * generator can page over.
  */
case class InvalidPaginatedOperation(service: String, name: String)
    extends AwsGeneratorFailure

/** A failure of the underlying code, with the exception in `reason`. */
case class UnknownError(reason: Throwable) extends AwsGeneratorFailure
