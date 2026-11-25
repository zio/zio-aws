package zio.aws.core.config

import zio.aws.core.BuilderHelper
import zio.aws.core.httpclient.{HttpClient, ServiceHttpCapabilities}
import software.amazon.awssdk.awscore.client.builder.{
  AwsAsyncClientBuilder,
  AwsClientBuilder
}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import zio._

import scala.jdk.CollectionConverters._

/** Sets up the AWS SDK client builder of each generated service. While a generated service layer builds its client, it calls `configure` and then `configureHttpClient` on the builder. One `AwsConfig` layer can serve every service of a program. Build it with `default`, `customized` or `configured` from the companion object.
  *
  * @see
  *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
  */
trait AwsConfig {
  /** Applies this configuration's client settings to `builder` and returns it, for example the region and the credentials provider of a [[CommonAwsConfig]]. */
  def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](
      builder: Builder
  ): Task[Builder]

  /** Returns `builder` with the `SdkAsyncHttpClient` that the [[zio.aws.core.httpclient.HttpClient]] chooses for a service with the capabilities `serviceCaps`. It fails when the `HttpClient` fails, for example when an HTTP/2-only client is asked for a client for a service without HTTP/2 support. */
  def configureHttpClient[
      Client,
      Builder <: AwsAsyncClientBuilder[Builder, Client]
  ](builder: Builder, serviceCaps: ServiceHttpCapabilities): Task[Builder]
}

/** Layers that build an [[AwsConfig]] from an [[zio.aws.core.httpclient.HttpClient]].
  *
  * @see
  *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
  */
object AwsConfig {
  /** An `AwsConfig` that sets only the HTTP client and leaves every other setting of the builder unchanged, so the region, the credentials and the retries come from the AWS SDK's defaults unless a service's own customization sets them. It is `customized(ClientCustomization.None)`.
    *
    * {{{
    * import zio._
    * import zio.aws.core.httpclient.HttpClient
    *
    * // `httpClient` comes from an HTTP client module, such as zio-aws-netty
    * def awsConfig(httpClient: ZLayer[Any, Throwable, HttpClient]): ZLayer[Any, Throwable, AwsConfig] =
    *   httpClient >>> AwsConfig.default
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
    */
  val default: ZLayer[HttpClient, Nothing, AwsConfig] = customized(
    ClientCustomization.None
  )

  /** Returns an `AwsConfig` that sets the HTTP client and passes each client builder to `customization`. It reads no [[CommonAwsConfig]]. An exception thrown by `customization` fails `configure`.
    *
    * {{{
    * import zio._
    * import zio.aws.core.httpclient.HttpClient
    * import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
    * import software.amazon.awssdk.regions.Region
    *
    * val inEuWest1: ClientCustomization = new ClientCustomization {
    *   override def customize[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Builder =
    *     builder.region(Region.EU_WEST_1)
    * }
    *
    * val awsConfig: ZLayer[HttpClient, Nothing, AwsConfig] = AwsConfig.customized(inEuWest1)
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
    */
  def customized(
      customization: ClientCustomization
  ): ZLayer[HttpClient, Nothing, AwsConfig] =
    ZLayer {
      for {
        httpClient <- ZIO.service[HttpClient]
      } yield new AwsConfig {
        override def configure[Client, Builder <: AwsClientBuilder[
          Builder,
          Client
        ]](builder: Builder): Task[Builder] =
          ZIO.attempt(customization.customize[Client, Builder](builder))

        override def configureHttpClient[
            Client,
            Builder <: AwsAsyncClientBuilder[Builder, Client]
        ](
            builder: Builder,
            serviceCaps: ServiceHttpCapabilities
        ): Task[Builder] =
          httpClient.clientFor(serviceCaps).map(builder.httpClient)
      }
    }

  /** Returns an `AwsConfig` that sets the HTTP client and applies a [[CommonAwsConfig]] to each client builder:
    *
    *  - `region` and `endpointOverride` are set when they are defined;
    *  - `credentialsProvider` is always set;
    *  - when `commonClientConfig` is defined, it replaces the builder's override configuration with one that has its extra headers, its timeouts and its profile name, and the retry policy `RetryPolicy.none()`, so the AWS SDK client does not retry a failed request itself. When `commonClientConfig` is `None`, the SDK's default retries stay in place.
    *
    * {{{
    * import zio._
    * import zio.aws.core.httpclient.HttpClient
    * import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
    * import software.amazon.awssdk.regions.Region
    * import java.net.URI
    *
    * val common: ZLayer[Any, Nothing, CommonAwsConfig] = ZLayer.succeed(
    *   CommonAwsConfig(
    *     region = Some(Region.US_EAST_1),
    *     credentialsProvider = AnonymousCredentialsProvider.create(),
    *     endpointOverride = Some(URI.create("http://localhost:4566")),
    *     commonClientConfig = None
    *   )
    * )
    *
    * // or read the same settings from the application's configuration
    * val fromConfig: ZLayer[Any, Config.Error, CommonAwsConfig] =
    *   ZLayer.fromZIO(ZIO.config(descriptors.commonAwsConfig))
    *
    * def awsConfig(httpClient: ZLayer[Any, Throwable, HttpClient]): ZLayer[Any, Throwable, AwsConfig] =
    *   (httpClient ++ common) >>> AwsConfig.configured()
    * }}}
    *
    * @see
    *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
    */
  def configured(): ZLayer[HttpClient & CommonAwsConfig, Nothing, AwsConfig] =
    ZLayer {
      for {
        httpClient <- ZIO.service[HttpClient]
        commonConfig <- ZIO.service[CommonAwsConfig]
      } yield new AwsConfig {
        override def configure[Client, Builder <: AwsClientBuilder[
          Builder,
          Client
        ]](builder: Builder): Task[Builder] = {
          val builderHelper: BuilderHelper[Client] = BuilderHelper.apply
          import builderHelper._
          ZIO.attempt {
            val b0 =
              builder
                .optionallyWith(commonConfig.endpointOverride)(
                  _.endpointOverride
                )
                .optionallyWith(commonConfig.region)(_.region)
                .credentialsProvider(commonConfig.credentialsProvider)

            commonConfig.commonClientConfig match {
              case Some(commonClientConfig) =>
                val clientOverrideBuilderHelper
                    : BuilderHelper[ClientOverrideConfiguration] =
                  BuilderHelper.apply
                import clientOverrideBuilderHelper._
                val overrideBuilder =
                  ClientOverrideConfiguration
                    .builder()
                    .headers(
                      commonClientConfig.extraHeaders.map { case (key, value) =>
                        key -> value.asJava
                      }.asJava
                    )
                    .retryPolicy(RetryPolicy.none())
                    .optionallyWith(
                      commonClientConfig.apiCallTimeout
                    )(_.apiCallTimeout)
                    .optionallyWith(
                      commonClientConfig.apiCallAttemptTimeout
                    )(_.apiCallAttemptTimeout)
                    .optionallyWith(commonClientConfig.defaultProfileName)(
                      _.defaultProfileName
                    )

                b0.overrideConfiguration(overrideBuilder.build())
              case None =>
                b0
            }
          }
        }

        override def configureHttpClient[
            Client,
            Builder <: AwsAsyncClientBuilder[Builder, Client]
        ](
            builder: Builder,
            serviceCaps: ServiceHttpCapabilities
        ): Task[Builder] =
          httpClient.clientFor(serviceCaps).map(builder.httpClient)
      }
    }
}
