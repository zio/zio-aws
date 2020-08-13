package io.github.vigoo.zioaws.core

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
  def fromThrowable(reason: Throwable): AwsError =
    GenericAwsError(reason)

  def unwrapOptionField[T](name: String, value: Option[T]): ZIO[Any, AwsError, T] =
    value match {
      case Some(value) => ZIO.succeed(value)
      case None => ZIO.fail(FieldIsNone(name))
    }
}