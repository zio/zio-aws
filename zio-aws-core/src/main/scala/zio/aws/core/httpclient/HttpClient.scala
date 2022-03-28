package zio.aws.core.httpclient

import zio.aws.core.httpclient.Protocol.{Dual, Http11, Http2}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.{Scope, Task, ZIO, ZLayer}

trait HttpClient {
  def clientFor(
      serviceCaps: ServiceHttpCapabilities
  ): Task[SdkAsyncHttpClient]
}
object HttpClient {
  def fromScopedPerProtocol[R, E, A <: SdkAsyncHttpClient](
      http11Client: ZIO[R with Scope, E, A],
      http2Client: ZIO[R with Scope, E, A]
  )(protocol: Protocol): ZLayer[R, E, HttpClient] =  
    ZLayer.scoped[R] {
      fromScopedPerProtocolScoped[R, E, A](http11Client, http2Client)(protocol)
    }

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
              Task.succeed(client)
          }
        }
      case Http2 =>
        http2Client.map { client =>
          new HttpClient {
            override def clientFor(
                serviceCaps: ServiceHttpCapabilities
            ): Task[SdkAsyncHttpClient] =
              if (serviceCaps.supportsHttp2) {
                Task.succeed(client)
              } else {
                Task.fail(
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
              Task.succeed(http2)
            } else {
              Task.succeed(http11)
            }
        }
    }

  def clientFor(
      serviceCaps: ServiceHttpCapabilities
  ): ZIO[HttpClient, Throwable, SdkAsyncHttpClient] =
    ZIO.serviceWithZIO(_.clientFor(serviceCaps))

}
