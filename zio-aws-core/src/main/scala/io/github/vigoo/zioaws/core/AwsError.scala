package io.github.vigoo.zioaws.core

sealed trait AwsError {
  def toThrowable: Throwable
}

case class GenericAwsError(reason: Throwable) extends AwsError {
  override def toThrowable: Throwable = reason
}

object AwsError {
  def fromThrowable(reason: Throwable): AwsError =
    GenericAwsError(reason)
}