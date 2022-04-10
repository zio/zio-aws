package zio.aws.integtests

import zio.aws.core.aspects._
import zio.aws.core._
import zio._

trait Logging {
  val callLogging: AwsCallAspect[Any] =
    new AwsCallAspect[Any] {
      override final def apply[R, E >: AwsError, A <: Described[_]](
          f: ZIO[R, E, A]
      )(implicit trace: ZTraceElement): ZIO[R, E, A] = {
        f.either.timed
          .flatMap {
            case (duration, Right(r)) =>
              ZIO.succeed(r)
            case (duration, Left(error)) =>
              Console
                .printLine(
                  s"AWS call FAILED in $duration with $error"
                )
                .ignore *> ZIO.fail(error)
          }
      }
    }
}
