package io.github.vigoo.zioaws.core

import izumi.reflect.Tag
import zio.{Has, TagK, ZIO, ZLayer, ZManaged}

package object aspects {
  trait Aspect[-R, +E] { self =>
    def apply[R1 <: R, E1 >: E, A](f: ZIO[R1, E1, A]): ZIO[R1, E1, A]

    final def >>>[R1 <: R, E1 >: E](that: Aspect[R1, E1]): Aspect[R1, E1] =
      andThen(that)

    final def andThen[R1 <: R, E1 >: E](that: Aspect[R1, E1]): Aspect[R1, E1] =
      new Aspect[R1, E1] {
        def apply[R2 <: R1, E2 >: E1, A](f: ZIO[R2, E2, A]): ZIO[R2, E2, A] =
          that(self(f))
      }
  }

  object Aspect {
    def identity[R, E]: Aspect[R, E] = new Aspect[R, E] {
      override final def apply[R1 <: R, E1 >: E, A](f: ZIO[R1, E1, A]): ZIO[R1, E1, A] = f
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

  private[core] implicit class DescrubedZIOSyntax[R, E, A](f: ZIO[R, E, Described[A]]) {
    final def unwrap: ZIO[R, E, A] = f.map(_.value)
  }

  implicit class ZLayerSyntax[ROutR : Tag, RIn <: ROutR, E, ROut[_] <: AwsServiceBase[RIn, ROut] : TagK](layer: ZLayer[RIn, E, Has[ROut[ROutR]]]) {
    def @@[RIn1 <: RIn : Tag](aspect: Aspect[RIn1, AwsError]): ZLayer[RIn1, E, Has[ROut[RIn1]]] =
      ZLayer.fromManaged[RIn1, E, ROut[RIn1]] {
        ZManaged.environment[RIn1].flatMap { r =>
          layer.build.map(_.get.withAspect(aspect, r))
        }
      }
  }
}
