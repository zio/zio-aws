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

object NettyHttpClient {
  val default: ZLayer[Any, Throwable, HttpClient] =
    customized(Protocol.Http11, identity)

  val dual: ZLayer[Any, Throwable, HttpClient] =
    customized(Protocol.Dual, identity)

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
