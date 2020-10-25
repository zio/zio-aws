package io.github.vigoo.zioaws.core

import izumi.reflect.Tag
import zio.{Has, ZIO, ZLayer, ZManaged}

trait Aspect[-R, +E] { self =>
  def apply[R1 <: R, E1 >: E, A](f: ZIO[R1, E1, A]): ZIO[R1, E1, A]

  final def >>>[R1 <: R, E1 >: E](that: Aspect[R1, E1]): Aspect[R1, E1] =
    andThen(that)

  /**
   * Returns a new aspect that represents the sequential composition of this
   * aspect with the specified one.
   */
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
