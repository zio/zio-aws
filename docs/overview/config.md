---
id: overview_config
title: Configuration
---

# Configuration

## Common configuration

Each _service module_ depends on the `AwsConfig` layer. This layer is responsible for setting up the 
AWS Java SDK's async client, by setting the [underlying HTTP engine](overview_http) and all the common
settings. You can use the following layers to provide `AwsConfig`:

#### Default
`AwsConfig.default` requires a `HttpClient` as dependency, but does not customize any other setting of the client

#### Fully customized
`AwsConfig.customized(customization)` gives the freedom to customize the creation of the AWS async client directly by modifying it's `Builder`

#### Configured
`AwsConfig.configured()` is the *recommended* way to construct an `AwsConfig`. Beside requiring a `HttpClient` it also has `ZConfig[CommonAwsConfig]` as dependency.
The `CommonAwsConfig` value can be either provided from code for example by `ZLayer.succeed(CommonAwsConfig(...))` or it can
be read from any of the supported config sources by [zio-config](https://zio.github.io/zio-config/).

Note that **AWS level retries are disabled** by the configuration layer and it is not exposed in the `CommonAwsConfig` data structure either. The reason for this is that the recommended way to handle retries is to use [aspects on the service layers](overview_aspects).
 
See the following table about the possible configuration values. Please note that the underlying HTTP engine also has its own
specific configuration which is described [on the page about the HTTP engines](overview_http). 

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
