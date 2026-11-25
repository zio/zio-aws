package zio.aws.core.httpclient

import zio.aws.core.httpclient.Protocol.{Dual, Http11, Http2}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.{Scope, Task, ZIO, ZLayer}

/** Gives each generated service the AWS SDK `SdkAsyncHttpClient` it sends its requests with. The HTTP client modules, such as zio-aws-netty, provide `HttpClient` layers, and [[zio.aws.core.config.AwsConfig]] needs one.
  *
  * @see
  *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
  */
trait HttpClient {
  /** Returns the client to use for a service with the capabilities `serviceCaps`, or fails when this `HttpClient` has no client that the service can use. */
  def clientFor(
      serviceCaps: ServiceHttpCapabilities
  ): Task[SdkAsyncHttpClient]
}
/** Constructors and an accessor for [[HttpClient]].
  *
  * @see
  *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
  */
object HttpClient {
  /** Returns a layer that builds an [[HttpClient]] from two scoped effects, one that creates an HTTP/1.1 client and one that creates an HTTP/2 client, and a [[Protocol]] that chooses between them:
    *
    *  - `Protocol.Http11` runs only `http11Client`, and every service gets that client;
    *  - `Protocol.Http2` runs only `http2Client`; services that support HTTP/2 get it, and `clientFor` fails for the others with `java.lang.UnsupportedOperationException: The http client only supports HTTP 2 but the client requires HTTP 1.1`;
    *  - `Protocol.Dual` runs `http11Client` and then `http2Client`; services that support HTTP/2 get the HTTP/2 client and the others get the HTTP/1.1 client.
    *
    * The clients live in the layer's scope, so their finalizers run when the layer is released. On Scala 2, give the type parameters explicitly when the effects need only a `Scope`, as the example does: otherwise `R` is inferred as `Scope` and the layer needs a `Scope` as input.
    *
    * {{{
    * import zio._
    * import software.amazon.awssdk.http.async.SdkAsyncHttpClient
    *
    * // services that support HTTP/2 get `http2`, the others get `http11`
    * def dual(
    *     http11: ZIO[Scope, Throwable, SdkAsyncHttpClient],
    *     http2: ZIO[Scope, Throwable, SdkAsyncHttpClient]
    * ): ZLayer[Any, Throwable, HttpClient] =
    *   HttpClient.fromScopedPerProtocol[Any, Throwable, SdkAsyncHttpClient](http11, http2)(Protocol.Dual)
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
    */
  def fromScopedPerProtocol[R, E, A <: SdkAsyncHttpClient](
      http11Client: ZIO[R with Scope, E, A],
      http2Client: ZIO[R with Scope, E, A]
  )(protocol: Protocol): ZLayer[R, E, HttpClient] =
    ZLayer.scoped[R] {
      fromScopedPerProtocolScoped[R, E, A](http11Client, http2Client)(protocol)
    }

  /** Returns the same [[HttpClient]] as `fromScopedPerProtocol`, as a scoped effect instead of a layer. The clients live in the scope of the effect. */
  def fromScopedPerProtocolScoped[R, E, A <: SdkAsyncHttpClient](
      http11Client: ZIO[R with Scope, E, A],
      http2Client: ZIO[R with Scope, E, A]
  )(protocol: Protocol): ZIO[R with Scope, E, HttpClient] =
    protocol match {
      case Http11 =>
        http11Client.map { client =>
          new HttpClient {
            override def clientFor(
                serviceCaps: ServiceHttpCapabilities
            ): Task[SdkAsyncHttpClient] =
              ZIO.succeed(client)
          }
        }
      case Http2 =>
        http2Client.map { client =>
          new HttpClient {
            override def clientFor(
                serviceCaps: ServiceHttpCapabilities
            ): Task[SdkAsyncHttpClient] =
              if (serviceCaps.supportsHttp2) {
                ZIO.succeed(client)
              } else {
                ZIO.fail(
                  new UnsupportedOperationException(
                    "The http client only supports HTTP 2 but the client requires HTTP 1.1"
                  )
                )
              }
          }
        }
      case Dual =>
        for {
          http11 <- http11Client
          http2 <- http2Client
        } yield new HttpClient {
          override def clientFor(
              serviceCaps: ServiceHttpCapabilities
          ): Task[SdkAsyncHttpClient] =
            if (serviceCaps.supportsHttp2) {
              ZIO.succeed(http2)
            } else {
              ZIO.succeed(http11)
            }
        }
    }

  /** Returns the client that the [[HttpClient]] in the environment chooses for a service with the capabilities `serviceCaps`. */
  def clientFor(
      serviceCaps: ServiceHttpCapabilities
  ): ZIO[HttpClient, Throwable, SdkAsyncHttpClient] =
    ZIO.serviceWithZIO(_.clientFor(serviceCaps))

}
