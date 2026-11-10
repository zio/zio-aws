---
id: aspects
title: "Aspects"
description: "Reference for AwsCallAspect, the built-in logging and duration aspects, the Described result type and the @@@ operator that attaches aspects to service layers."
keywords:
  - "ZIO AWS"
  - "AwsCallAspect"
  - "Aspects"
  - "Logging"
  - "Metrics"
---

# Aspects

An aspect of type `AwsCallAspect[R]` changes how the operations of a service behave, without changing the operations themselves. Aspects are the library's recommended way to add logging, metrics, retries or rate limiting to AWS calls. The [Add Aspects for Logging and Metrics](./guide-add-aspects.md) guide shows how to write and attach one.

## AwsCallAspect

`AwsCallAspect` is a type alias in the `zio.aws.core.aspects` package:

```scala
type AwsCallAspect[-R] = ZIOAspect[Nothing, R, AwsError, AwsError, Nothing, Described[_]]
```

An aspect transforms a `ZIO[R, E, A]` where the result `A` is a `Described[_]`. Every generated operation returns its result wrapped in `Described`, so an aspect always knows which service and operation it is looking at.

`AwsCallAspect.identity` is an aspect that changes nothing. It is useful as a starting point when you build aspects in a loop.

## Described

`Described` pairs a result with the name of the call that produced it:

```scala
case class ServiceCallDescription(service: String, operation: String)
case class Described[+A](value: A, description: ServiceCallDescription)
```

The `service` and `operation` fields are the names used by the built-in aspects. A custom aspect reads them from `description`, and it returns the same `Described` value, so the operation's result reaches the caller unchanged.

## Built-in Aspects

The library provides two aspects. Both only observe the call, so they do not change its result.

```scala
val callLogging: AwsCallAspect[Any]
def callDuration(prefix: String, boundaries: MetricKeyType.Histogram.Boundaries): AwsCallAspect[Any]
```

`callLogging` logs one line for each call in the form `[service/operation] ran for <duration>`, using ZIO's logging. It logs successful calls only, because a failed call never reaches the point where the duration is logged. Use `logFailures`-style aspects, shown in the how-to guide, to log errors.

`callDuration` records each call's duration in a histogram named `<prefix>_aws_call`. The histogram has the tags `aws_service` and `aws_operation`, and the `boundaries` are the upper limits of its buckets in seconds. Create the boundaries with `MetricKeyType.Histogram.Boundaries.fromChunk`.

## Attaching an Aspect

The `@@@` operator attaches an aspect to a service layer. It is available once you import `zio.aws.core.aspects._`:

```scala
def @@@[RIn1 <: RIn](aspect: AwsCallAspect[RIn1]): ZLayer[RIn1, E, ROut]
```

The operator works on any `ZLayer` whose output is a service that extends `AspectSupport`, which every generated service does. The result is a layer of the same service, with the aspect applied to each of its operations. Aspects combine with `>>>`:

```scala
val observed = Ec2.live @@@ (logFailures >>> callLogging)
```

## Writing an Aspect

An aspect is a class that extends `AwsCallAspect[R]` and implements `apply`. The library's own aspects use this signature:

```scala
override final def apply[R, E, A <: Described[_]](f: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
```

The `Described[_]` bound is part of the signature, so an aspect cannot be applied to a plain `ZIO` that is not a generated AWS call.

## Going Further

- The [Add Aspects for Logging and Metrics](./guide-add-aspects.md) guide walks through attaching the built-in aspects and writing one of your own.
- The [Examples](./examples.md) page shows a complete program that builds its layers from `AwsConfig`.
