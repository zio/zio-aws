package zio.aws.netty

import zio.aws.core.BuilderHelper
import zio.aws.core.httpclient.{HttpClient, Protocol}
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.http.{
  Protocol => AwsProtocol,
  TlsKeyManagersProvider,
  TlsTrustManagersProvider
}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.{
  Http2Configuration,
  NettyNioAsyncHttpClient,
  ProxyConfiguration => AwsProxyConfiguration
}
import zio.{Scope, ZIO, ZLayer}

/** Builds a `zio.aws.core.httpclient.HttpClient` layer backed by the AWS SDK's
  * Netty client, `NettyNioAsyncHttpClient`. Each layer creates its Netty
  * clients when it is built and closes them when its scope ends.
  *
  * {{{
  * import zio._
  * import zio.aws.core.config.AwsConfig
  *
  * val awsConfig: ZLayer[Any, Throwable, AwsConfig] =
  *   NettyHttpClient.default >>> AwsConfig.default
  * }}}
  *
  * @see
  *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
  */
object NettyHttpClient {

  /** A layer with one Netty client that uses HTTP/1.1. It returns this client
    * for every service, also for the services that support HTTP/2. It is
    * `customized(Protocol.Http11)`.
    *
    * {{{
    * import zio._
    * import zio.aws.core.httpclient.{HttpClient, ServiceHttpCapabilities}
    *
    * val clientName: Task[String] =
    *   HttpClient
    *     .clientFor(ServiceHttpCapabilities(supportsHttp2 = false))
    *     .map(_.clientName())
    *     .provide(NettyHttpClient.default)
    * // result: "NettyNio"
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
    */
  val default: ZLayer[Any, Throwable, HttpClient] =
    customized(Protocol.Http11, identity)

  /** A layer with two Netty clients, one for HTTP/1.1 and one for HTTP/2. It
    * returns the HTTP/2 client for the services that support HTTP/2 and the
    * HTTP/1.1 client for the rest. It is `customized(Protocol.Dual)`.
    *
    * {{{
    * import zio._
    * import zio.aws.core.httpclient.{HttpClient, ServiceHttpCapabilities}
    *
    * val sameClient: Task[Boolean] =
    *   (for {
    *     http11 <- HttpClient.clientFor(ServiceHttpCapabilities(supportsHttp2 = false))
    *     http2  <- HttpClient.clientFor(ServiceHttpCapabilities(supportsHttp2 = true))
    *   } yield http11 eq http2).provide(NettyHttpClient.dual)
    * // result: false
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
    */
  val dual: ZLayer[Any, Throwable, HttpClient] =
    customized(Protocol.Dual, identity)

  /** Builds a layer with the Netty clients that `protocol` needs, each created
    * from `NettyNioAsyncHttpClient.builder()` after `customization` changes it.
    * The layer sets the builder's protocol after `customization` runs, so a
    * protocol set in `customization` is replaced. With `Protocol.Http2`, asking
    * for the client of a service without HTTP/2 support fails with an
    * `UnsupportedOperationException`.
    *
    * {{{
    * import zio._
    * import zio.aws.core.httpclient.{HttpClient, Protocol}
    *
    * val httpClient: ZLayer[Any, Throwable, HttpClient] =
    *   NettyHttpClient.customized(
    *     Protocol.Http11,
    *     _.maxConcurrency(20).connectionMaxIdleTime(java.time.Duration.ofSeconds(30))
    *   )
    * }}}
    *
    * @param protocol
    *   `Protocol.Http11` builds one HTTP/1.1 client, `Protocol.Http2` one
    *   HTTP/2 client, and `Protocol.Dual` one of each
    * @param customization
    *   changes the SDK builder before the layer builds the client
    * @see
    *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
    */
  def customized(
      protocol: Protocol,
      customization: NettyNioAsyncHttpClient.Builder => NettyNioAsyncHttpClient.Builder =
        identity
  ): ZLayer[Any, Throwable, HttpClient] = {
    def create(
        awsProtocol: AwsProtocol
    ): ZIO[Scope, Throwable, SdkAsyncHttpClient] =
      ZIO
        .fromAutoCloseable(
          ZIO.attempt(
            customization(
              NettyNioAsyncHttpClient
                .builder()
            )
              .protocol(awsProtocol)
              .build()
          )
        )

    HttpClient.fromScopedPerProtocol[Any, Throwable, SdkAsyncHttpClient](
      create(AwsProtocol.HTTP1_1),
      create(AwsProtocol.HTTP2)
    )(protocol)
  }

  /** Builds a layer with Netty clients configured from the
    * [[NettyClientConfig]] in the environment. `NettyClientConfig.protocol`
    * chooses the clients, as `protocol` does in `customized`. The TLS key and
    * trust manager providers are parameters because configuration cannot
    * describe them.
    *
    * {{{
    * import zio._
    * import zio.aws.core.httpclient.HttpClient
    *
    * val settings: IO[Config.Error, NettyClientConfig] =
    *   ConfigProvider
    *     .fromMap(Map("maxConcurrency" -> "20", "protocol" -> "HTTP/1.1"))
    *     .load(descriptors.nettyClientConfig)
    *
    * val httpClient: ZLayer[Any, Throwable, HttpClient] =
    *   ZLayer.fromZIO(settings) >>> NettyHttpClient.configured()
    * }}}
    *
    * @param tlsKeyManagersProvider
    *   passed to the builder's `tlsKeyManagersProvider`; `None` keeps the SDK's
    *   default
    * @param tlsTrustManagersProvider
    *   passed to the builder's `tlsTrustManagersProvider`; `None` keeps the
    *   SDK's default
    * @see
    *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
    */
  def configured(
      tlsKeyManagersProvider: Option[TlsKeyManagersProvider] = None,
      tlsTrustManagersProvider: Option[TlsTrustManagersProvider] = None
  ): ZLayer[NettyClientConfig, Throwable, HttpClient] = {
    def create(
        awsProtocol: AwsProtocol
    ): ZIO[NettyClientConfig with Scope, Throwable, SdkAsyncHttpClient] =
      ZIO
        .fromAutoCloseable(ZIO.service[NettyClientConfig].flatMap { config =>
          ZIO.attempt {
            val builderHelper: BuilderHelper[NettyNioAsyncHttpClient] =
              BuilderHelper.apply
            import builderHelper._

            val builder0: NettyNioAsyncHttpClient.Builder =
              NettyNioAsyncHttpClient
                .builder()
                .protocol(awsProtocol)
                .maxConcurrency(config.maxConcurrency)
                .maxPendingConnectionAcquires(
                  config.maxPendingConnectionAcquires
                )
                .readTimeout(config.readTimeout)
                .writeTimeout(config.writeTimeout)
                .connectionTimeout(config.connectionTimeout)
                .connectionAcquisitionTimeout(
                  config.connectionAcquisitionTimeout
                )
                .connectionTimeToLive(config.connectionTimeToLive)
                .connectionMaxIdleTime(config.connectionMaxIdleTime)
                .useIdleConnectionReaper(config.useIdleConnectionReaper)
                .optionallyWith(config.sslProvider)(_.sslProvider)
                .optionallyWith(config.proxyConfiguration)(builder =>
                  proxy =>
                    builder.proxyConfiguration(
                      AwsProxyConfiguration
                        .builder()
                        .host(proxy.host)
                        .port(proxy.port)
                        .scheme(proxy.scheme.asString)
                        .nonProxyHosts(proxy.nonProxyHosts.asJava)
                        .build()
                    )
                )
                .optionallyWith(config.http2)(builder =>
                  http2 =>
                    builder.http2Configuration(
                      Http2Configuration
                        .builder()
                        .healthCheckPingPeriod(http2.healthCheckPingPeriod)
                        .maxStreams(http2.maxStreams)
                        .initialWindowSize(http2.initialWindowSize)
                        .build()
                    )
                )
                .optionallyWith(tlsKeyManagersProvider)(
                  _.tlsKeyManagersProvider
                )
                .optionallyWith(tlsTrustManagersProvider)(
                  _.tlsTrustManagersProvider
                )

            val builder1 =
              config.channelOptions.options.foldLeft(builder0) {
                case (b, opt) =>
                  b.putChannelOption(opt.key, opt.value)
              }

            builder1.build()
          }
        })

    ZLayer.scoped {
      ZIO.service[NettyClientConfig].flatMap { config =>
        HttpClient.fromScopedPerProtocolScoped(
          create(AwsProtocol.HTTP1_1),
          create(AwsProtocol.HTTP2)
        )(config.protocol)
      }
    }
  }

}
