package zio.aws.core

import izumi.reflect.Tag
import zio._

package object aspects {
  trait AwsCallAspect[-R]
    extends ZIOAspect[Nothing, R, AwsError, AwsError, Nothing, Described[_]]

  object AwsCallAspect {
    def identity[R]: AwsCallAspect[R] =
      new AwsCallAspect[R] {
        override final def apply[R, E, A](
            f: ZIO[R, E, A]
        )(implicit trace: ZTraceElement): ZIO[R, E, A] = f
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
