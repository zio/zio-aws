---
layout: docs
title: Configuration
---

# Configuration

## Common configuration

Each _service module_ depends on the `AwsConfig` layer. This layer is responsible for setting up the 
AWS Java SDK's async client, by setting the [underlying HTTP engine](http.html) and all the common
settings. You can use the following layers to provide `AwsConfig`:

#### Default
`core.config.default` requires a `HttpClient` as dependency, but does not customize any other setting of the client

#### Fully customized
`core.config.customized(customization)` gives the freedom to customize the creation of the AWS async client directly by modifying it's `Builder`

#### Configured
`core.config.configured()` is the *recommended* way to construct an `AwsConfig`. Beside requiring a `HttpClient` it also has `ZConfig[CommonAwsConfig]` as dependency.
The `CommonAwsConfig` value can be either provided from code for example by `ZLayer.succeed(CommonAwsConfig(...))` or it can
be read from any of the supported config sources by [zio-config](https://zio.github.io/zio-config/).
 
See the following table about the possible configuration values. Please note that the underlying HTTP engine also has its own
specific configuration which is described [on the page about the HTTP engines](http.html). 

```scala mdoc:passthrough
import zio.config._
import io.github.vigoo.zioaws.core.config.descriptors._

val docs = generateDocs(commonAwsConfig)
println(docs.toTable.toGithubFlavouredMarkdown)
```

Note that **AWS level retries are disabled** by the configuration layer and it is not exposed in the `CommonAwsConfig` data structure either. The reason for 
this is that the recommended way to handle retries is to use [aspects on the service layers](aspects.html).

## Service layer
Each AWS service's generated client has it own layer that depends on `AwsConfig`. It is possible to reuse the same `AwsConfig` layer
for multiple AWS service clients, sharing a common configuration. Usually the service client does not require any additional configuration,
in this case the `live` layer can be used, for example:

```scala
awsConfig >>> (ec2.live ++ elasticbeanstalk.live)
```
