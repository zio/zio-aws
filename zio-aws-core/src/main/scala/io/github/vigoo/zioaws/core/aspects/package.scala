package io.github.vigoo.zioaws.core

import izumi.reflect.Tag
import zio.{Has, ZIO, ZLayer, ZManaged}

package object aspects {
  trait AwsCallAspect[-R] { self =>
    def apply[R1 <: R, A](f: ZIO[R1, AwsError, Described[A]]): ZIO[R1, AwsError, Described[A]]

    final def >>>[R1 <: R](that: AwsCallAspect[R1]): AwsCallAspect[R1] =
      andThen(that)

    final def andThen[R1 <: R](that: AwsCallAspect[R1]): AwsCallAspect[R1] =
      new AwsCallAspect[R1] {
        def apply[R2 <: R1, A](f: ZIO[R2, AwsError, Described[A]]): ZIO[R2, AwsError, Described[A]] =
          that(self(f))
      }
  }

  object AwsCallAspect {
    def identity[R, E]: AwsCallAspect[R] = new AwsCallAspect[R] {
      override final def apply[R1 <: R, A](f: ZIO[R1, AwsError, Described[A]]): ZIO[R1, AwsError, Described[A]] = f
    }
  }

  case class ServiceCallDescription(service: String, operation: String)
  case class Described[A](value: A, description: ServiceCallDescription)

  private[core] implicit class StringSyntax(service: String) {
    final def /(op: String): ServiceCallDescription = ServiceCallDescription(service, op)
  }

  private[core] implicit class ZIOSyntax[R, E, A](f: ZIO[R, E, A]) {
    final def ?(description: ServiceCallDescription): ZIO[R, E, Described[A]] =
      f.map(Described(_, description))
  }

  private[core] implicit class DescribedZIOSyntax[R, E, A](f: ZIO[R, E, Described[A]]) {
    final def unwrap: ZIO[R, E, A] = f.map(_.value)
  }

  trait AspectSupport[Self] {
    def withAspect[R](newAspect: AwsCallAspect[R], r: R): Self
  }

  implicit class ZLayerSyntax[RIn, E, ROut <: AspectSupport[ROut] : Tag](layer: ZLayer[RIn, E, Has[ROut]]) {
    def @@[RIn1 <: RIn : Tag](aspect: AwsCallAspect[RIn1]): ZLayer[RIn1, E, Has[ROut]] =
      ZLayer.fromManaged[RIn1, E, ROut] {
        ZManaged.environment[RIn1].flatMap { r =>
          layer.build.map(_.get.withAspect(aspect, r))
        }
      }
  }
}
