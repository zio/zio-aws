---
id: configuration
title: Configuration
---

# Configuration

## Common configuration

Each _service module_ depends on the `AwsConfig` layer. This layer is responsible for setting up the 
AWS Java SDK's async client, by setting the [underlying HTTP engine](http.md) and all the common
settings. You can use the following layers to provide `AwsConfig`:

#### Default
`AwsConfig.default` requires a `HttpClient` as dependency, but does not customize any other setting of the client

#### Fully customized
`AwsConfig.customized(customization)` gives the freedom to customize the creation of the AWS async client directly by modifying it's `Builder`

#### Configured
`AwsConfig.configured()` is the *recommended* way to construct an `AwsConfig`. Beside requiring a `HttpClient` it also requires a `CommonAwsConfig` service.
The `CommonAwsConfig` value can be either provided from code, for example by `ZLayer.succeed(CommonAwsConfig(...))`, or it can
be read from any of the supported config sources by [zio-config](https://zio.dev/zio-config/), for example with `ZLayer.fromZIO(ZIO.config(commonAwsConfig))`, where `commonAwsConfig` is the descriptor in `zio.aws.core.config.descriptors`. The [Configure Credentials and Region](./guide-configure-credentials-and-region.md) guide shows both ways.

When `commonClientConfig` is set, `configured()` also turns off the AWS SDK's own retries. Retries are not exposed as a setting in `CommonAwsConfig`. The recommended way to handle retries is to use [aspects on the service layers](aspects.md).
 
See the following table about the possible configuration values. Please note that the underlying HTTP engine also has its own
specific configuration which is described [on the page about the HTTP engines](http.md). 

```scala mdoc:passthrough
import zio.config._
import zio.aws.core.config.descriptors._

val docs = generateDocs(commonAwsConfig)
println(docs.toTable.toGithubFlavouredMarkdown)
```

## Service layer
Each AWS service's generated client has it own layer that depends on `AwsConfig`. It is possible to reuse the same `AwsConfig` layer
for multiple AWS service clients, sharing a common configuration. Usually the service client does not require any additional configuration,
in this case the `live` layer can be used, for example:

```scala
program.provide(
    awsConfig,
    Ec2.live,
    ElasticBeanstalk.live
)
```
