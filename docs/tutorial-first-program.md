---
id: tutorial-first-program
title: "Tutorial: Your First ZIO AWS Program"
description: "Learn how ZIO AWS wires an HTTP client, a common configuration and a service module together, then call one AWS operation from a ZIO program."
keywords:
  - "ZIO AWS"
  - "AWS SDK"
  - "ZLayer"
  - "Ec2"
  - "AwsConfig"
---

# Tutorial: Your First ZIO AWS Program

Welcome to ZIO AWS! This tutorial is for newcomers who know basic Scala and have some familiarity with [ZIO](https://zio.dev) effects and layers. You don't need any prior experience with AWS to follow along, although the tutorial assumes you have an AWS account whose credentials are available on your machine.

## Learning Objectives

By the end of this tutorial, you will understand:

- What the AWS Java SDK's async client is, and why ZIO AWS wraps it
- How the HTTP client, the common configuration and the service module are stacked as layers
- How to call an AWS operation and read its result as a ZIO effect

We'll learn these concepts through:

1. Adding the dependencies
2. Understanding the layer stack
3. Calling one operation
4. Running the program

We recommend reading from top to bottom, because each section builds on the previous one.

## Background

The AWS Java SDK v2 exposes an async client per service, such as `Ec2AsyncClient`. Its methods return `CompletableFuture` values, and its responses are immutable Java objects in which optional fields may be `null`.

ZIO AWS wraps each service client in a ZIO service. Every operation becomes a function that returns a `ZIO`, and every response is exposed through getter methods that return `ZIO` values as well. A missing field fails the effect with an `AwsError` instead of returning `null`. The error types are explained in [AWS Errors](./aws-error.md).

## 1. Adding the Dependencies

We need the core module, one generated service module and one HTTP client module. For this tutorial we use EC2 and the Netty HTTP client:

```scala
libraryDependencies += "dev.zio" %% "zio-aws-core"  % "<version>"
libraryDependencies += "dev.zio" %% "zio-aws-ec2"   % "<version>"
libraryDependencies += "dev.zio" %% "zio-aws-netty" % "<version>"
```

Each AWS service has its own artifact, so you add only the services you use. See [Artifacts](./artifacts.md) for the full list.

## 2. Understanding the Layer Stack

ZIO AWS builds the AWS client from three layers, each depending on the one before it:

```
NettyHttpClient.default   (provides HttpClient)
        |
        v
AwsConfig.default         (provides AwsConfig, needs HttpClient)
        |
        v
Ec2.live                  (provides Ec2, needs AwsConfig)
```

The HTTP client does the network I/O. `AwsConfig` applies the common settings, such as region and credentials, to every AWS client. `Ec2.live` builds the EC2 client using `AwsConfig` and exposes the EC2 operations as a ZIO service.

Before we write the program, we declare the import list that covers all three layers and the ZIO API:

```scala mdoc:compile-only
import zio._
import zio.aws.core.AwsError
import zio.aws.core.config.AwsConfig
import zio.aws.ec2.Ec2
import zio.aws.ec2.model._
import zio.aws.netty.NettyHttpClient
```

The `zio.aws.ec2.model._` import brings in the request and response types. The `zio.aws.core.AwsError` import is the error type of every AWS call.

## 3. Calling One Operation

We call `DescribeRegions`, which lists the regions that EC2 offers. Each operation is a method on the `Ec2` service, and its request is a case class with default values, so an empty request is valid:

```scala mdoc:compile-only
val listRegions: ZIO[Ec2, AwsError, Unit] =
  for {
    response <- Ec2.describeRegions(DescribeRegionsRequest())
    regions  <- response.getRegions
    _ <- ZIO.foreachDiscard(regions) { region =>
           region.getRegionName
             .flatMap(name => Console.printLine(s"Region: $name").ignore)
         }
  } yield ()
```

The code above:

- `Ec2.describeRegions(DescribeRegionsRequest())` performs the call. The effect is typed `ZIO[Ec2, AwsError, DescribeRegionsResponse.ReadOnly]`, so the `Ec2` service is required in the environment.
- `response.getRegions` reads the `regions` field. The field is optional in the AWS model, so the getter fails with `AwsError` when the field is absent.
- `region.getRegionName` reads the name of each region the same way.
- `Console.printLine(...).ignore` prints the line and discards the `IOException`, which keeps the error type as `AwsError`.

The effect does nothing until it runs. Building the `listRegions` value makes no network call.

## 4. Running the Program

We provide the three layers to the program and run it as a `ZIOAppDefault`:

```scala mdoc:compile-only
object ListRegionsApp extends ZIOAppDefault {
  override def run: URIO[ZIOAppArgs & Scope, ExitCode] =
    listRegions
      .provide(
        NettyHttpClient.default,
        AwsConfig.default,
        Ec2.live
      )
      .exitCode
}
```

When you run this application, it prints one line for each EC2 region. The region and the credentials come from the standard AWS sources, such as the `AWS_REGION` environment variable and your AWS profile, because `AwsConfig.default` does not set them. The [Configuration](./configuration.md) page shows how to set them explicitly.

:::tip
The `provide` call builds the layers in dependency order. You can also compose them yourself with `>>>`, as the [examples](./examples.md) page does.
:::

## What You've Learned

In this tutorial, you learned:

- Why ZIO AWS wraps the AWS Java SDK's async clients in ZIO services
- How `NettyHttpClient`, `AwsConfig` and `Ec2` stack as layers
- How to call an operation and read its optional fields as ZIO effects
- How to provide the layers and run the program

You now have the foundation for any AWS service that ZIO AWS supports. The next step is to see how to change the configuration and handle failures.

## Where to Go Next

- **Ready to use this in practice?** Start with the how-to guide [Configure Credentials and Region](./guide-configure-credentials-and-region.md).
- **Want to handle failures?** Read [Handle AWS Errors](./guide-handle-errors.md).
- **Want to see the full API?** Read the reference pages for [Wrappers](./wrappers.md) and [Configuration](./configuration.md).
