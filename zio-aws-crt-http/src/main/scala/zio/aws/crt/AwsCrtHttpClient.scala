package zio.aws.crt

import zio.aws.core.BuilderHelper
import zio.aws.core.httpclient.{HttpClient, ServiceHttpCapabilities}
import zio.{Scope, ZIO, ZLayer}

import software.amazon.awssdk.http.crt.{
  AwsCrtAsyncHttpClient,
  ConnectionHealthConfiguration,
  ProxyConfiguration,
  TcpKeepAliveConfiguration
}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient

object AwsCrtHttpClient {
  val default: ZLayer[Any, Throwable, HttpClient] = customized(identity)

  def customized(
      customization: AwsCrtAsyncHttpClient.Builder => AwsCrtAsyncHttpClient.Builder =
        identity
  ): ZLayer[Any, Throwable, HttpClient] = {
    def create(): ZIO[Scope, Throwable, SdkAsyncHttpClient] =
      ZIO.fromAutoCloseable(
        ZIO.attempt(
          customization(
            AwsCrtAsyncHttpClient.builder()
          ).build()
        )
      )

    ZLayer.scoped {
      for {
        client <- create()
      } yield new HttpClient {
        override def clientFor(
            serviceCaps: ServiceHttpCapabilities
        ): ZIO[Any, Throwable, SdkAsyncHttpClient] = ZIO.succeed(client)
      }
    }
  }

  def configured(): ZLayer[AwsCrtHttpClientConfig, Throwable, HttpClient] = {
    def create(): ZIO[
      AwsCrtHttpClientConfig with Scope,
      Throwable,
      SdkAsyncHttpClient
    ] =
      ZIO.service[AwsCrtHttpClientConfig].flatMap { config =>
        ZIO.fromAutoCloseable(
          ZIO
            .attempt {
              val builderHelper: BuilderHelper[AwsCrtAsyncHttpClient] =
                BuilderHelper.apply
              import builderHelper._

              AwsCrtAsyncHttpClient
                .builder()
                .maxConcurrency(config.maxConcurrency)
                .readBufferSizeInBytes(config.readBufferSizeInBytes)
                .optionallyWith(config.proxyConfiguration)(builder =>
                  proxy =>
                    builder.proxyConfiguration(
                      ProxyConfiguration
                        .builder()
                        .scheme(proxy.scheme)
                        .host(proxy.host)
                        .port(proxy.port)
                        .username(proxy.username)
                        .password(proxy.password)
                        .build(
                        )
                    )
                )
                .optionallyWith(config.connectionHealthConfiguration)(builder =>
                  health =>
                    builder.connectionHealthConfiguration(
                      ConnectionHealthConfiguration
                        .builder()
                        .minimumThroughputInBps(
                          health.minimumThroughputInBps
                        )
                        .minimumThroughputTimeout(
                          health.minimumThroughputTimeout
                        )
                        .build()
                    )
                )
                .connectionMaxIdleTime(config.connectionMaxIdleTime)
                .connectionTimeout(config.connectionTimeout)
                // .connectionAcquisitionTimeout(config.connectionAcquisitionTimeout)
                .optionallyWith(config.tcpKeepAliveConfiguration)(builder =>
                  tcpKeepAlive =>
                    builder.tcpKeepAliveConfiguration(
                      TcpKeepAliveConfiguration
                        .builder()
                        .keepAliveInterval(tcpKeepAlive.keepAliveInterval)
                        .keepAliveTimeout(tcpKeepAlive.keepAliveTimeout)
                        .build()
                    )
                )
                .postQuantumTlsEnabled(config.postQuantumTlsEnabled)
                .build()
            }
        )
      }

    ZLayer.scoped {
      for {
        client <- create()
      } yield new HttpClient {
        override def clientFor(
            serviceCaps: ServiceHttpCapabilities
        ): ZIO[Any, Throwable, SdkAsyncHttpClient] = ZIO.succeed(client)
      }
    }
  }
}
