package zio.aws.core

import java.util.concurrent.CompletionException
import zio.ZIO
import zio.prelude.data.Optional

/** The error type of every ZIO AWS operation. It is either a
  * [[GenericAwsError]] that wraps the exception the AWS SDK reported, or a
  * [[FieldIsNone]] that a generated `get` accessor returns when a response
  * field is absent.
  *
  * @see
  *   [[https://zio.dev/zio-aws/wrappers/ Wrappers]]
  */
sealed trait AwsError {

  /** Returns this error as a `Throwable`: the wrapped exception for a
    * [[GenericAwsError]], and a `java.util.NoSuchElementException` whose
    * message is the field name for a [[FieldIsNone]].
    *
    * {{{
    * import zio._
    *
    * val missing: Throwable = FieldIsNone("queueUrl").toThrowable
    * // java.util.NoSuchElementException: queueUrl
    *
    * def asTask[A](call: ZIO[Any, AwsError, A]): Task[A] = call.mapError(_.toThrowable)
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/wrappers/ Wrappers]]
    */
  def toThrowable: Throwable
}

/** An error that wraps an exception from the AWS SDK or from the HTTP client,
  * such as a service error response or a connection failure.
  */
case class GenericAwsError(reason: Throwable) extends AwsError {
  override def toThrowable: Throwable = reason
}

/** The error of a generated `get` accessor, such as `getQueueUrl`, when the
  * response field named `field` is absent. Its [[AwsError#toThrowable]] is a
  * `java.util.NoSuchElementException` whose message is `field`.
  */
case class FieldIsNone(field: String) extends AwsError {
  override def toThrowable: Throwable = new NoSuchElementException(field)
}

/** Constructors of [[AwsError]]. */
object AwsError {

  /** Wraps `reason` in a [[GenericAwsError]]. When `reason` is a
    * `java.util.concurrent.CompletionException` with a cause, the cause is
    * wrapped instead; only this one level is removed, and a
    * `CompletionException` without a cause, or any other exception such as an
    * `ExecutionException`, is wrapped as it is.
    *
    * {{{
    * import java.util.concurrent.CompletionException
    *
    * val error: AwsError =
    *   AwsError.fromThrowable(new CompletionException(new IllegalStateException("throttled")))
    * // GenericAwsError(java.lang.IllegalStateException: throttled)
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/wrappers/ Wrappers]]
    */
  def fromThrowable(reason: Throwable): AwsError = {
    val innerReason = reason match {
      case e: CompletionException =>
        Option(e.getCause).getOrElse(e)
      case e => e
    }

    GenericAwsError(innerReason)
  }

  /** Returns an effect that succeeds with the value inside `value`, or fails
    * with `FieldIsNone(name)` when it is `Optional.Absent`. The generated `get`
    * accessors call it. `value` is read when this method is called, not when
    * the effect runs.
    *
    * @param name
    *   the field name that the [[FieldIsNone]] error carries
    */
  def unwrapOptionField[T](
      name: String,
      value: => Optional[T]
  ): ZIO[Any, AwsError, T] =
    value match {
      case Optional.Present(value) => ZIO.succeed(value)
      case Optional.Absent         => ZIO.fail(FieldIsNone(name))
    }
}
