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
        f.timeout(30.seconds)
          .tapError(error => ZIO.debug(s"AWS call failed with $error"))
          .flatMap {
            case Some(result) => ZIO.succeed(result)
            case None =>
              ZIO.fail(
                GenericAwsError(new RuntimeException(s"AWS call timed out"))
              )
          }
          .retryN(10)
      }
    }
}
