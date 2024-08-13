package zio.aws.core

import zio._
import zio.metrics._

package object aspects {
  type AwsCallAspect[-R] =
    ZIOAspect[Nothing, R, AwsError, AwsError, Nothing, Described[_]]

  object AwsCallAspect {
    val identity: AwsCallAspect[Any] =
      new AwsCallAspect[Any] {
        override final def apply[R, E, A](
            f: ZIO[R, E, A]
        )(implicit trace: Trace): ZIO[R, E, A] = f
      }
  }

  val callLogging: AwsCallAspect[Any] =
    new AwsCallAspect[Any] {
      override final def apply[R, E, A <: Described[_]](
          f: ZIO[R, E, A]
      )(implicit trace: Trace): ZIO[R, E, A] = {
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
      boundaries: MetricKeyType.Histogram.Boundaries
  ): AwsCallAspect[Any] =
    new AwsCallAspect[Any] {
      override final def apply[R, E, A <: Described[_]](
          f: ZIO[R, E, A]
      )(implicit trace: Trace): ZIO[R, E, A] = {
        f.timed.flatMap { case (duration, r) =>
          val durationInSeconds =
            duration.getSeconds + (duration.getNano / 1000000000.0)
          Metric
            .histogram(
              s"${prefix}_aws_call",
              boundaries
            )
            .tagged("aws_service", r.description.service)
            .tagged("aws_operation", r.description.operation)
            .update(durationInSeconds)
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

  implicit class ZLayerSyntax[RIn, E, ROut <: AspectSupport[ROut]: Tag](
      layer: ZLayer[RIn, E, ROut]
  ) {
    def @@@[RIn1 <: RIn](
        aspect: AwsCallAspect[RIn1]
    ): ZLayer[RIn1, E, ROut] =
      ZLayer.scoped[RIn1] {
        ZIO.environment[RIn1].flatMap { r =>
          layer.build.map(_.get.withAspect(aspect, r))
        }
      }
  }
}
