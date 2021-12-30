package zio.aws.core

import izumi.reflect.Tag
import zio._

package object aspects {
  type AwsCallAspect[-R] =
    ZIOAspect[Nothing, R, AwsError, AwsError, Nothing, Described[_]]

  object AwsCallAspect {
    val identity: AwsCallAspect[Any] =
      new AwsCallAspect[Any] {
        override final def apply[R, E, A](
            f: ZIO[R, E, A]
        )(implicit trace: ZTraceElement): ZIO[R, E, A] = f
      }
  }

  val callLogging: AwsCallAspect[Clock] =
    new AwsCallAspect[Clock] {
      override final def apply[R <: Clock, E, A <: Described[_]](
          f: ZIO[R, E, A]
      )(implicit trace: ZTraceElement): ZIO[R, E, A] = {
        f.timed.flatMap { case (duration, r) =>
          ZIO
            .log(
              s"[${r.description.service}/${r.description.operation}] ran for $duration"
            )
            .as(r)
        }
      }
    }

  def callDuration(
      prefix: String,
      boundaries: ZIOMetric.Histogram.Boundaries
  ): AwsCallAspect[Clock] =
    new AwsCallAspect[Clock] {
      override final def apply[R <: Clock, E, A <: Described[_]](
          f: ZIO[R, E, A]
      )(implicit trace: ZTraceElement): ZIO[R, E, A] = {
        f.timed.flatMap { case (duration, r) =>
          val durationInSeconds =
            duration.getSeconds + (duration.getNano / 1000000000.0)
          ZIOMetric
            .observeHistogram(
              s"${prefix}_aws_call",
              boundaries,
              MetricLabel("aws_service", r.description.service),
              MetricLabel("aws_operation", r.description.operation)
            )
            .observe(durationInSeconds)
            .zipRight(f)
        }
      }
    }

  case class ServiceCallDescription(service: String, operation: String)
  case class Described[+A](value: A, description: ServiceCallDescription)

  private[core] implicit class StringSyntax(service: String) {
    final def /(op: String): ServiceCallDescription =
      ServiceCallDescription(service, op)
  }

  private[core] implicit class ZIOSyntax[R, E, A](f: ZIO[R, E, A]) {
    final def ?(description: ServiceCallDescription): ZIO[R, E, Described[A]] =
      f.map(Described(_, description))
  }

  private[core] implicit class DescribedZIOSyntax[R, E, A](
      f: ZIO[R, E, Described[A]]
  ) {
    final def unwrap: ZIO[R, E, A] = f.map(_.value)
  }

  trait AspectSupport[Self] {
    def withAspect[R](newAspect: AwsCallAspect[R], r: ZEnvironment[R]): Self
  }

  implicit class ZLayerSyntax[RIn, E, ROut <: AspectSupport[
    ROut
  ]: Tag: IsNotIntersection](
      layer: ZLayer[RIn, E, ROut]
  ) {
    def @@[RIn1 <: RIn: Tag](
        aspect: AwsCallAspect[RIn1]
    ): ZLayer[RIn1, E, ROut] =
      ZLayer.fromManaged[RIn1, E, ROut] {
        ZManaged.environment[RIn1].flatMap { r =>
          layer.build.map(_.get.withAspect(aspect, r))
        }
      }
  }
}
