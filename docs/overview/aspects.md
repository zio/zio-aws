---
id: overview_aspects
title: Aspects
---

# Aspects

It is possible to define _aspects_ of type `AwsCallAspect[R]` that can modify the behavior of the AWS client modules. This can be used for example 
to add logging or metrics to the AWS clients and it's also the recommended way to handle retries or apply rate limiting and other similar patterns.

To define an aspect, create an instance of the `AwsCallAspect` trait:

```scala mdoc:invisible
import zio._
import zio.aws.core.aspects._
import zio.aws.core.AwsError
```

```scala mdoc
  val callLogging: AwsCallAspect[Clock & Console] =
    new AwsCallAspect[Clock & Console] {
      override final def apply[R <: Clock & Console, E >: AwsError, A <: Described[_]](
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
```

This aspect can attached to a _client layer_ with the `@@` operator. Multiple aspects can be composed with `>>>`.

To see a full example, check [example #2](https://github.com/vigoo/zio-aws/blob/master/examples/example2/src/main/scala/Main.scala).
