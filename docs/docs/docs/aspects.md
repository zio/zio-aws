---
layout: docs
title: Aspects
---

# Aspects

It is possible to define _aspects_ of type `AwsCallAspect[R]` that can modify the behavior of the AWS client modules. This can be used for example 
to add logging or metrics to the AWS clients and it's also the recommended way to handle retries or apply rate limiting and other similar patterns.

To define an aspect, create an instance of the `AwsCallAspect` trait:

```scala mdoc:invisible
import zio._
import zio.clock._
import zio.console._
import zio.aws.core.aspects._
import zio.aws.core.AwsError
```

```scala mdoc
val callLogging: AwsCallAspect[Clock with Console] =
  new AwsCallAspect[Clock with Console] {
    override final def apply[R1 <: Clock with Console, A](
        f: ZIO[R1, AwsError, Described[A]]
    ): ZIO[R1, AwsError, Described[A]] = {
      f.timed.flatMap { case (duration, r @ Described(result, description)) =>
        console
          .putStrLn(
            s"[${description.service}/${description.operation}] ran for $duration"
          )
		  .ignore
          .as(r)
      }
    }
  }
```

This aspect can attached to a _client layer_ with the `@@` operator. Multiple aspects can be composed with `>>>`.

To see a full example, check [example #2](https://github.com/vigoo/zio-aws/blob/master/examples/example2/src/main/scala/Main.scala).
