---
id: http
title: "HTTP Clients"
description: "Reference for the four HTTP client modules that ZIO AWS can use, with their factory functions, layer requirements and configuration keys."
keywords:
  - "ZIO AWS"
  - "HttpClient"
  - "Netty"
  - "http4s"
  - "Akka HTTP"
  - "AWS CRT"
---

# HTTP Clients

ZIO AWS sends every request through an `HttpClient` layer, which the `AwsConfig` layer depends on. Each HTTP client is its own module, and each module provides one or more factory functions that build an `HttpClient` layer. The [Choose an HTTP Client](./guide-choose-http-client.md) guide explains how to pick one.

The following modules are available:

- `zio-aws-netty` uses Netty, which is the HTTP client the AWS SDK uses by default
- `zio-aws-http4s` uses the Blaze client from http4s
- `zio-aws-akka-http` uses Akka HTTP, through the [aws-spi-akka-http library](https://github.com/matsluni/aws-spi-akka-http)
- `zio-aws-crt-http` uses the AWS Common Runtime (CRT) client

## Netty

The object `zio.aws.netty.NettyHttpClient` provides these factories:

```scala
val default: ZLayer[Any, Throwable, HttpClient]
val dual: ZLayer[Any, Throwable, HttpClient]
def customized(protocol: Protocol, customization: NettyNioAsyncHttpClient.Builder => NettyNioAsyncHttpClient.Builder = identity): ZLayer[Any, Throwable, HttpClient]
def configured(tlsKeyManagersProvider: Option[TlsKeyManagersProvider] = None, tlsTrustManagersProvider: Option[TlsTrustManagersProvider] = None): ZLayer[NettyClientConfig, Throwable, HttpClient]
```

`default` uses HTTP/1.1. `dual` uses HTTP/2 for the services that support it and HTTP/1.1 for the rest. `customized` takes the `Protocol` (`Protocol.Http11`, `Protocol.Http2` or `Protocol.Dual`) and a function that changes the Netty builder. `configured` reads the settings from a `NettyClientConfig` layer, which you can load with `ZIO.config(nettyClientConfig)` from `zio.aws.netty.descriptors`. Only the TLS key and trust manager providers are passed as parameters, because they cannot be described in configuration.

The configuration keys are listed in the following table:

```scala mdoc:passthrough
import zio.config._
import zio.aws.netty.descriptors._

val docs = generateDocs(nettyClientConfig)
println(docs.toTable.toGithubFlavouredMarkdown)
```

## Akka HTTP

The object `zio.aws.akkahttp.AkkaHttpClient` provides the `client` factory:

```scala
def client(
    connectionPoolSettings: Option[ConnectionPoolSettings] = None,
    executionContext: Option[ExecutionContext] = None
): ZLayer[ActorSystem, Throwable, HttpClient]
```

The layer needs an `ActorSystem` in its environment. The client uses the [standard akka-http settings](https://doc.akka.io/docs/akka-http/current/configuration.html) from the application's Lightbend config, and it is not described with ZIO Config descriptors.

## http4s

The object `zio.aws.http4s.Http4sClient` provides these factories:

```scala
val default: ZLayer[Any, Throwable, HttpClient]
def customized(f: BlazeClientBuilder[Task] => BlazeClientBuilder[Task]): ZLayer[Any, Throwable, HttpClient]
def configured(
    sslContext: Option[SSLContext] = tryDefaultSslContext,
    maxConnectionsPerRequestKey: Option[RequestKey => Int] = None,
    asynchronousChannelGroup: Option[AsynchronousChannelGroup] = None,
    additionalSocketOptions: Seq[OptionValue[_]] = Seq.empty
): ZLayer[BlazeClientConfig, Throwable, HttpClient]
```

`default` uses the default Blaze settings. `customized` passes the `BlazeClientBuilder` to your function. `configured` reads the timeouts and other settings from a `BlazeClientConfig` layer, and the function parameters cover the settings that cannot be described in configuration:

```scala mdoc:passthrough
 import zio.aws.http4s.descriptors._

 val docs2 = generateDocs(blazeClientConfig)
 println(docs2.toTable.toGithubFlavouredMarkdown)
```

## AWS CRT

The object `zio.aws.crt.AwsCrtHttpClient` provides these factories:

```scala
val default: ZLayer[Any, Throwable, HttpClient]
def customized(customization: AwsCrtAsyncHttpClient.Builder => AwsCrtAsyncHttpClient.Builder = identity): ZLayer[Any, Throwable, HttpClient]
def configured(): ZLayer[AwsCrtHttpClientConfig, Throwable, HttpClient]
```

`default` uses the CRT defaults, `customized` changes the CRT builder, and `configured` reads its settings from an `AwsCrtHttpClientConfig` layer:

```scala mdoc:passthrough
import zio.aws.crt.descriptors._

val docs3 = generateDocs(awsCrtHttpClientConfig)
println(docs3.toTable.toGithubFlavouredMarkdown)
```
