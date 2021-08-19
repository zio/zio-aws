package io.github.vigoo.zioaws.integtests

import io.github.vigoo.zioaws.core.aspects._
import io.github.vigoo.zioaws.core._
import zio._
import zio.clock._
import zio.console._

trait Logging {
  val callLogging: AwsCallAspect[Clock with Console] =
    new AwsCallAspect[Clock with Console] {
      override final def apply[R1 <: Clock with Console, A](
          f: ZIO[R1, AwsError, Described[A]]
      ): ZIO[R1, AwsError, Described[A]] = {
        f.either.timed
          .flatMap {
            case (duration, Right(r @ Described(result, description))) =>
              ZIO.succeed(r)
            case (duration, Left(error)) =>
              console
                .putStrLn(
                  s"AWS call FAILED in $duration with $error"
                )
                .ignore *> ZIO.fail(error)
          }
      }
    }
}
