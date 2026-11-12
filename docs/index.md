---
id: index
title: "Overview"
---

[ZIO AWS](https://zio.dev/zio-aws) is an AWS wrapper for [ZIO](https://zio.dev) for _all_ AWS services, built on the AWS Java SDK v2.

[![Production Ready](https://img.shields.io/badge/Project%20Stage-Production%20Ready-brightgreen.svg)](https://github.com/zio/zio/wiki/Project-Stages) ![CI Badge](https://github.com/zio/zio-aws/workflows/CI/badge.svg) [![Sonatype Releases](https://img.shields.io/maven-central/v/dev.zio/zio-aws-core_2.13.svg?label=Sonatype%20Release)](https://central.sonatype.com/artifact/dev.zio/zio-aws-core_2.13) [![Sonatype Snapshots](https://img.shields.io/badge/Sonatype%20Snapshot-published-blue)](https://central.sonatype.com/repository/maven-snapshots/dev/zio/zio-aws-core_2.13/) [![javadoc](https://javadoc.io/badge2/dev.zio/zio-aws-core_2.13/javadoc.svg)](https://javadoc.io/doc/dev.zio/zio-aws-core_2.13) [![ZIO AWS](https://img.shields.io/github/stars/zio/zio-aws?style=social)](https://github.com/zio/zio-aws)

## Introduction

The goal is to have access to all AWS functionality for cases when only a simple, direct access is needed from a ZIO application, or to be used as a building block for higher level wrappers around specific services.

Key features of ZIO AWS:

- A common configuration layer, `AwsConfig`
- A ZIO service module per AWS service
- A wrapper for all operations on all services
- HTTP client implementations for functional Scala HTTP libraries, injected through ZIO's module system
- ZStream wrappers around paginated and streaming operations
- Service-specific extra configuration
- More idiomatic Scala request and response types wrapping the Java classes
- [Aspects](aspects.md) to take care of additional concerns like logging, metrics and retries

## Where to Start

If you are new to ZIO AWS, read the [first program tutorial](tutorial-first-program.md) and then the guides that match your task:

- [Configure Credentials and Region](guide-configure-credentials-and-region.md)
- [Choose an HTTP Client](guide-choose-http-client.md)
- [Paginate and Stream Results](guide-paginate-and-stream.md)
- [Handle AWS Errors](guide-handle-errors.md)
- [Add Aspects for Logging and Metrics](guide-add-aspects.md)
- [Test Code That Uses AWS](guide-test-code-with-stubs.md)

The reference pages describe the [artifacts](artifacts.md), [wrappers](wrappers.md), [configuration](configuration.md), [HTTP clients](http.md), [aspects](aspects.md) and [errors](aws-error.md) in detail.

## Design

The library consists of a core module and one generated library for _each_ AWS service, based on the official JSON schema from the AWS Java SDK's repository. By only providing a wrapper on top of the Java SDK the code generator does not have to know all the implementation details and features of the schema.

## Installation

There are many artifacts [published](artifacts.md), one for each AWS service. You pick the services you need.

## Example

The following example uses the _ElasticBeanstalk_ and _EC2_ APIs:

> **Note**
>
> See the [artifacts](artifacts.md) page for the latest release.

The dependencies are declared as follows:

```scala
libraryDependencies += "dev.zio" %% "zio-aws-core"             % "<version>"
libraryDependencies += "dev.zio" %% "zio-aws-ec2"              % "<version>"
libraryDependencies += "dev.zio" %% "zio-aws-elasticbeanstalk" % "<version>"
libraryDependencies += "dev.zio" %% "zio-aws-netty"            % "<version>"
```

And here is the example code:

```scala mdoc:compile-only
import zio.aws.core.AwsError
import zio.aws.core.config.AwsConfig
import zio.aws.ec2.Ec2
import zio.aws.ec2.model._
import zio.aws.ec2.model.primitives._
import zio.aws.elasticbeanstalk.ElasticBeanstalk
import zio.aws.elasticbeanstalk.model._
import zio.aws.elasticbeanstalk.model.primitives._
import zio.aws.netty.NettyHttpClient
import zio._
import zio.stream._

object ZIOAWSExample extends ZIOAppDefault {
  val program: ZIO[Ec2 & ElasticBeanstalk, AwsError, Unit] =
    for {
      appsResult <- ElasticBeanstalk.describeApplications(
        DescribeApplicationsRequest(applicationNames = List(ApplicationName("my-service")))
      )
      app <- appsResult.getApplications.map(_.headOption)
      _ <- app match {
        case Some(appDescription) =>
          for {
            applicationName <- appDescription.getApplicationName
            _ <- Console
              .printLine(
                s"Got application description for $applicationName"
              )
              .ignore

            envStream = ElasticBeanstalk.describeEnvironments(
              DescribeEnvironmentsRequest(applicationName =
                applicationName
              )
            )

            _ <- envStream.run(ZSink.foreach { env =>
              env.getEnvironmentName.flatMap { environmentName =>
                (for {
                  environmentId <- env.getEnvironmentId
                  _ <- Console
                    .printLine(
                      s"Getting the EB resources of $environmentName"
                    )
                    .ignore

                  resourcesResult <-
                    ElasticBeanstalk.describeEnvironmentResources(
                      DescribeEnvironmentResourcesRequest(environmentId =
                        environmentId
                      )
                    )
                  resources <- resourcesResult.getEnvironmentResources
                  _ <- Console
                    .printLine(
                      s"Getting the EC2 instances in $environmentName"
                    )
                    .ignore
                  instances <- resources.getInstances
                  instanceIds <- ZIO.foreach(instances)(_.getId)
                  _ <- Console
                    .printLine(
                      s"Instance IDs are ${instanceIds.mkString(", ")}"
                    )
                    .ignore

                  reservationsStream = Ec2.describeInstances(
                    DescribeInstancesRequest(instanceIds = instanceIds.map(id => zio.aws.ec2.model.primitives.InstanceId(ResourceId.unwrap(id))))
                  )
                  _ <- reservationsStream.run(ZSink.foreach { reservation =>
                    reservation.getInstances
                      .flatMap { instances =>
                        ZIO.foreach(instances) { instance =>
                          for {
                            id <- instance.getInstanceId
                            typ <- instance.getInstanceType
                            launchTime <- instance.getLaunchTime
                            _ <- Console.printLine(s"  instance $id:").ignore
                            _ <- Console.printLine(s"    type: $typ").ignore
                            _ <- Console
                              .printLine(
                                s"    launched at: $launchTime"
                              )
                              .ignore
                          } yield ()
                        }
                      }
                  })
                } yield ()).catchAll { error =>
                  Console
                    .printLineError(
                      s"Failed to get info for $environmentName: $error"
                    )
                    .ignore
                }
              }
            })
          } yield ()
        case None =>
          ZIO.unit
      }
    } yield ()

  override def run: URIO[ZIOAppArgs, ExitCode] = {
    val httpClient = NettyHttpClient.default
    val awsConfig = httpClient >>> AwsConfig.default
    val aws = awsConfig >>> (Ec2.live ++ ElasticBeanstalk.live)

    program
      .provideLayer(aws)
      .either
      .flatMap {
        case Left(error) =>
          Console.printLineError(s"AWS error: $error").ignore.as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
```

## Higher Level AWS Libraries

The following libraries are built on top of `zio-aws` and provide higher level interfaces for specific AWS services:

- [ZIO DynamoDB](https://github.com/zio/zio-dynamodb)
- [ZIO Kinesis](https://github.com/svroonland/zio-kinesis)
- [ZIO SQS](https://github.com/zio/zio-sqs)

## Additional Resources

- There is a [blog post](https://vigoo.github.io/posts/2020-09-23-zioaws-code-generation.html) explaining how the code generator is implemented.
- [This post](https://vigoo.github.io/posts/2020-11-01-zioaws-zioquery.html) shows an example of using `zio-aws` together with [ZIO Query](https://zio.github.io/zio-query/).
- [Talk about generating libraries](https://www.youtube.com/watch?v=HCPTmytex3U) from Functional Scala 2021.
