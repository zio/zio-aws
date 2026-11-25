package zio.aws.crt

import zio.aws.core.BuilderHelper
import zio.aws.core.httpclient.{HttpClient, ServiceHttpCapabilities}
import zio.{Scope, ZIO, ZLayer}

import software.amazon.awssdk.http.crt.{AwsCrtAsyncHttpClient}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient

/**
 * Builds a `zio.aws.core.httpclient.HttpClient` layer backed by the AWS SDK's CRT client, `AwsCrtAsyncHttpClient`. Each layer builds one client, returns it for every service, and closes it when its scope ends.
 *
 * {{{
 * import zio._
 * import zio.aws.core.config.AwsConfig
 *
 * val awsConfig: ZLayer[Any, Throwable, AwsConfig] =
 *   AwsCrtHttpClient.default >>> AwsConfig.default
 * }}}
 *
 * @see [[https://zio.dev/zio-aws/http/ HTTP Clients]]
 */
object AwsCrtHttpClient {
  /**
   * A layer with a CRT client that uses the SDK's defaults.
   *
   * {{{
   * import zio._
   * import zio.aws.core.httpclient.{HttpClient, ServiceHttpCapabilities}
   *
   * val clientName: Task[String] =
   *   HttpClient
   *     .clientFor(ServiceHttpCapabilities(supportsHttp2 = false))
   *     .map(_.clientName())
   *     .provide(AwsCrtHttpClient.default)
   * // result: "AwsCommonRuntime"
   * }}}
   *
   * @see [[https://zio.dev/zio-aws/http/ HTTP Clients]]
   */
  val default: ZLayer[Any, Throwable, HttpClient] = customized(identity)

  /**
   * Builds a layer with a CRT client created from `AwsCrtAsyncHttpClient.builder()` after `customization` changes it.
   *
   * {{{
   * import zio._
   * import zio.aws.core.httpclient.HttpClient
   *
   * val httpClient: ZLayer[Any, Throwable, HttpClient] =
   *   AwsCrtHttpClient.customized(
   *     _.maxConcurrency(16).connectionTimeout(java.time.Duration.ofSeconds(2))
   *   )
   * }}}
   *
   * @see [[https://zio.dev/zio-aws/http/ HTTP Clients]]
   */
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

  /**
   * Builds a layer with a CRT client configured from the [[AwsCrtHttpClientConfig]] in the environment.
   *
   * {{{
   * import zio._
   * import zio.aws.core.httpclient.HttpClient
   *
   * val settings: IO[Config.Error, AwsCrtHttpClientConfig] =
   *   ConfigProvider
   *     .fromMap(
   *       Map(
   *         "maxConcurrency"        -> "16",
   *         "readBufferSizeInBytes" -> "1048576",
   *         "connectionMaxIdleTime" -> "30 seconds",
   *         "connectionTimeout"     -> "2 seconds",
   *         "postQuantumTlsEnabled" -> "false"
   *       )
   *     )
   *     .load(descriptors.awsCrtHttpClientConfig)
   *
   * val httpClient: ZLayer[Any, Throwable, HttpClient] =
   *   ZLayer.fromZIO(settings) >>> AwsCrtHttpClient.configured()
   * }}}
   *
   * @see [[https://zio.dev/zio-aws/http/ HTTP Clients]]
   */
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
                      software.amazon.awssdk.http.crt.ProxyConfiguration
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
                      software.amazon.awssdk.http.crt.ConnectionHealthConfiguration
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
                      software.amazon.awssdk.http.crt.TcpKeepAliveConfiguration
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
