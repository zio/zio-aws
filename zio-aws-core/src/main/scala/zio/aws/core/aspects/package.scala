package zio.aws.core

import zio._
import zio.metrics._

package object aspects {

  /** An aspect that wraps each call of a generated service, for example to log
    * it, measure it or retry it. It is a `ZIOAspect` whose error is
    * [[zio.aws.core.AwsError]] and whose result is a [[Described]] value, so
    * the aspect can read the service and operation names of the call. Attach
    * one to a service layer with `@@@`, and combine two with `>>>`.
    *
    * `R` is the environment the aspect needs.
    *
    * @see
    *   [[https://zio.dev/zio-aws/aspects/ Aspects]]
    */
  type AwsCallAspect[-R] =
    ZIOAspect[Nothing, R, AwsError, AwsError, Nothing, Described[_]]

  /** Predefined values of the `AwsCallAspect` type. */
  object AwsCallAspect {

    /** An aspect that returns each call unchanged. Generated services start
      * with it.
      */
    val identity: AwsCallAspect[Any] =
      new AwsCallAspect[Any] {
        override final def apply[R, E, A](
            f: ZIO[R, E, A]
        )(implicit trace: Trace): ZIO[R, E, A] = f
      }
  }

  /** An aspect that logs each successful call with `ZIO.log`, at the fiber's
    * current log level (`INFO` unless it is changed, for example with
    * `ZIO.logLevel`). The line holds the service and operation names and the
    * call's duration as a `java.time.Duration`, for example
    * `[sqs/ListQueues] ran for PT0.0019S`. A call that fails logs nothing, and
    * the call's result is unchanged.
    *
    * {{{
    * import zio._
    * import zio.aws.core.aspects._
    *
    * val call: ZIO[Any, AwsError, Described[Int]] =
    *   ZIO.succeed(Described(3, ServiceCallDescription("sqs", "ListQueues")))
    *
    * // logs a line such as: [sqs/ListQueues] ran for PT0.001931604S
    * val logged: ZIO[Any, AwsError, Described[Int]] = call @@ callLogging
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/aspects/ Aspects]]
    */
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

  /** Returns an aspect that records the duration of each successful call, in
    * seconds, in a histogram whose name is `prefix` followed by `_aws_call`,
    * tagged with `aws_service` and `aws_operation`. A call that fails records
    * nothing, and the call's result is unchanged.
    *
    * {{{
    * import zio._
    * import zio.metrics.MetricKeyType
    * import zio.aws.core.aspects._
    *
    * // records into the histogram `myapp_aws_call`, with buckets up to 0.1, 0.5, 1 and 5 seconds, plus one bucket for larger values
    * val timed: AwsCallAspect[Any] =
    *   callDuration("myapp", MetricKeyType.Histogram.Boundaries.fromChunk(Chunk(0.1, 0.5, 1.0, 5.0)))
    *
    * val loggedAndTimed: AwsCallAspect[Any] = callLogging >>> timed
    * }}}
    *
    * @param prefix
    *   the start of the histogram name
    * @param boundaries
    *   the upper bounds of the histogram's buckets, in seconds
    * @see
    *   [[https://zio.dev/zio-aws/aspects/ Aspects]]
    */
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
            .as(r)
        }
      }
    }

  /** Names one AWS call by its `service` and `operation`. `callLogging` writes
    * both in its log line, and `callDuration` uses them as the `aws_service`
    * and `aws_operation` tags.
    */
  case class ServiceCallDescription(service: String, operation: String)

  /** The result `value` of a call together with the [[ServiceCallDescription]]
    * of the call. Generated services wrap each result in `Described` while it
    * passes through their aspect, and unwrap it afterwards.
    */
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

  /** A service whose calls run through an `AwsCallAspect`. Every generated
    * service extends it.
    *
    * @see
    *   [[https://zio.dev/zio-aws/aspects/ Aspects]]
    */
  trait AspectSupport[Self] {

    /** Returns a copy of this service whose calls run through `newAspect`
      * instead of its current aspect, with `r` as the environment of the
      * aspect. To keep the current aspect as well, combine both with `>>>`
      * first.
      */
    def withAspect[R](newAspect: AwsCallAspect[R], r: ZEnvironment[R]): Self
  }

  /** Adds `@@@` to a layer of a service that extends [[AspectSupport]]. */
  implicit class ZLayerSyntax[RIn, E, ROut <: AspectSupport[ROut]: Tag](
      layer: ZLayer[RIn, E, ROut]
  ) {

    /** Returns a layer that builds this layer and then attaches `aspect` to the
      * service with `withAspect`, using the layer's input environment as the
      * environment of the aspect. The service's previous aspect is replaced.
      *
      * {{{
      * import zio._
      * import zio.aws.core.aspects._
      *
      * // `S` is a generated service, such as `zio.aws.s3.S3`
      * def withLogging[R, S <: AspectSupport[S]: Tag](layer: ZLayer[R, Throwable, S]): ZLayer[R, Throwable, S] =
      *   layer @@@ callLogging
      * }}}
      *
      * @see
      *   [[https://zio.dev/zio-aws/aspects/ Aspects]]
      */
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
