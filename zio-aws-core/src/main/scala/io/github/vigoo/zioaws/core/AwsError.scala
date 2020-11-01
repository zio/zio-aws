package io.github.vigoo.zioaws.core

import java.util.concurrent.CompletionException

import zio.ZIO

sealed trait AwsError {
  def toThrowable: Throwable
}

case class GenericAwsError(reason: Throwable) extends AwsError {
  override def toThrowable: Throwable = reason
}

case class FieldIsNone(field: String) extends AwsError {
  override def toThrowable: Throwable = new NoSuchElementException(field)
}

object AwsError {
  def fromThrowable(reason: Throwable): AwsError = {
    val innerReason = reason match {
      case e: CompletionException =>
        Option(e.getCause).getOrElse(e)
      case e => e
    }

    GenericAwsError(innerReason)
  }

  def unwrapOptionField[T](
      name: String,
      value: Option[T]
  ): ZIO[Any, AwsError, T] =
    value match {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(FieldIsNone(name))
    }
}
