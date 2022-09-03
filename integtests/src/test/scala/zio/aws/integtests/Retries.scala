package zio.aws.integtests

import zio.aws.core.aspects._
import zio.aws.core._
import zio._

trait Retries {
  val callRetries: AwsCallAspect[Any] =
    new AwsCallAspect[Any] {
      override final def apply[R, E >: AwsError, A <: Described[_]](
          f: ZIO[R, E, A]
      )(implicit trace: Trace): ZIO[R, E, A] = {
        f.retryN(10)
      }
    }
}
