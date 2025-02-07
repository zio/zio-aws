---
id: http
title: HTTP
---

# HTTP implementations

By default the AWS Java SDK uses _netty_ under the hood to make the HTTP client calls. `zio-aws` defines the http client
as a _layer_ (`HttpClient`) that has to be provided to the _AWS configuration layer_.

Currently the following implementations can be used:
- `zio-aws-netty` contains the default netty implementation packed as a layer
- `zio-aws-akka-http` is based on Matthias Lüneberg's [aws-spi-akka-http library](https://github.com/matsluni/aws-spi-akka-http)
- `zio-aws-http4s` is an implementation on top of _http4s_
- `zio-aws-crt-http` is an implementation on top of the _AWS Common Runtime_ (CRT) HTTP client

## Netty
The default HTTP implementation in the AWS Java SDK is _Netty_. To use it with the default settings, use the `netty.default`
layer to provide the `HttpClient` for the `AwsConfig` layer. It is also possible to customize the `NettyNioAsyncHttpClient`
directly by manipulation it's `Builder`, by using the `netty.customized(customization)` layer.

The recommended way for configuration is to use the zio-config support:

```scala
def configured(
      tlsKeyManagersProvider: Option[TlsKeyManagersProvider] = None,
      tlsTrustManagersProvider: Option[TlsTrustManagersProvider] = None
  ): ZLayer[ZConfig[NettyClientConfig], Throwable, HttpClient]
```

Everything except the TLS key and trust managers are described by the zio-config provided `NettyClientConfig` data structure.
See the following table for all the options:

```scala mdoc:passthrough
import zio.config._
import zio.aws.netty.descriptors._

val docs = generateDocs(nettyClientConfig)
println(docs.toTable.toGithubFlavouredMarkdown)
```

## Akka HTTP
The _Akka HTTP implementation_ can be chosen by using the `akkahttp.client()` layer for providing `HttpClient` to `AwsConfig`.
This implementation uses the [standard akka-http settings](https://doc.akka.io/docs/akka-http/current/configuration.html) from the application's _Lightbend config_,
it is not described with zio-config descriptors.

## http4s
Another alternative is the _http4s client_. To use the default settings, provide the `http4s.default` layer to `AwsConfig`. Customization by manipulating the builder
is also possible by `http4s.customized(customization)`. And similarly to the _Netty_ client, configuration is also possible via zio-config:

```scala mdoc:passthrough
 import zio.aws.http4s.descriptors._

 val docs2 = generateDocs(blazeClientConfig)
 println(docs2.toTable.toGithubFlavouredMarkdown)
```

## AWS CRT HTTP
The new _AWS Common Runtime_ (CRT) HTTP client can be used by providing the `crt.default` layer for providing `HttpClient` to `AwsConfig`.

```scala mdoc:passthrough
import zio.aws.crt.descriptors._

val docs3 = generateDocs(awsCrtHttpClientConfig)
println(docs3.toTable.toGithubFlavouredMarkdown)
```
