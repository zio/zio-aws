package io.github.vigoo.zioaws.core

sealed trait AwsError

case class GenericAwsError(reason: Throwable) extends AwsError

object AwsError {
  def fromThrowable(reason: Throwable): AwsError =
    GenericAwsError(reason)
}