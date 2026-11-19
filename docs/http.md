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


ZIO AWS sends every request through an `HttpClient` layer, which the `AwsConfig` layer depends on. Each HTTP client is its own module, and each module provides one or more factory functions that build an `HttpClient` layer. The [Choose an HTTP Client](./guide-choose-http-client.md) guide explains how to pick one.

## HttpClient

`HttpClient` is a trait in `zio.aws.core.httpclient`. When a service builds its AWS SDK client, `AwsConfig` asks the `HttpClient` for an SDK HTTP client that fits the service:

```scala
trait HttpClient {
  def clientFor(serviceCaps: ServiceHttpCapabilities): Task[SdkAsyncHttpClient]
}

case class ServiceHttpCapabilities(supportsHttp2: Boolean)
```

`supportsHttp2` is `true` for the services generated with HTTP/2 support, `kinesis` and `transcribestreaming`, and `false` for the rest. The `Protocol` type, with the cases `Protocol.Http11`, `Protocol.Http2` and `Protocol.Dual`, chooses how a client answers:

| Protocol          | HTTP/2 services | Other services                             |
| ----------------- | --------------- | ------------------------------------------ |
| `Protocol.Http11` | HTTP/1.1 client | HTTP/1.1 client                            |
| `Protocol.Http2`  | HTTP/2 client   | fails with `UnsupportedOperationException` |
| `Protocol.Dual`   | HTTP/2 client   | HTTP/1.1 client                            |

Only the Netty module takes a `Protocol`. The http4s, Akka HTTP and AWS CRT modules return the same client for every service.

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

// zio-config links nested tables with anchors that collide when a page has several tables
def keyTable(markdown: String): String =
  markdown
    .replaceAll("""\[([^\]]+)\]\(#[^)]*\)""", "$1")
    .replace("### Field Descriptions", "#### Field Descriptions")

val docs = generateDocs(nettyClientConfig)
println(keyTable(docs.toTable.toGithubFlavouredMarkdown))
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
 println(keyTable(docs2.toTable.toGithubFlavouredMarkdown))
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
println(keyTable(docs3.toTable.toGithubFlavouredMarkdown))
```

## Custom HTTP Clients

You rarely need this; it is an advanced API for using an `SdkAsyncHttpClient` that has no ZIO AWS module, such as one built by other code in your application. `HttpClient.fromScopedPerProtocol` builds an `HttpClient` layer from two scoped effects, one that creates the HTTP/1.1 client and one that creates the HTTP/2 client, and a `Protocol` that chooses between them:

```scala
object HttpClient {
  def fromScopedPerProtocol[R, E, A <: SdkAsyncHttpClient](
      http11Client: ZIO[R & Scope, E, A],
      http2Client: ZIO[R & Scope, E, A]
  )(protocol: Protocol): ZLayer[R, E, HttpClient]
}
```

The effects only run for the protocols the `Protocol` value uses, and the clients are closed when the layer's scope ends. `HttpClient.fromScopedPerProtocolScoped` returns the same `HttpClient` as a scoped `ZIO` instead of a layer. The Netty module uses this function itself:

```scala mdoc:compile-only
import zio._
import zio.aws.core.httpclient.{HttpClient, Protocol}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.http.{Protocol => SdkProtocol}

def nettyFor(protocol: SdkProtocol): ZIO[Scope, Throwable, SdkAsyncHttpClient] =
  ZIO.fromAutoCloseable(
    ZIO.attempt(NettyNioAsyncHttpClient.builder().protocol(protocol).build())
  )

val myHttpClient: ZLayer[Any, Throwable, HttpClient] =
  HttpClient.fromScopedPerProtocol(nettyFor(SdkProtocol.HTTP1_1), nettyFor(SdkProtocol.HTTP2))(Protocol.Dual)
```
