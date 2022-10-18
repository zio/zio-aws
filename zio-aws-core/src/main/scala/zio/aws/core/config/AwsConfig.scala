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

trait AwsConfig {
  def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](
      builder: Builder
  ): Task[Builder]

  def configureHttpClient[
      Client,
      Builder <: AwsAsyncClientBuilder[Builder, Client]
  ](builder: Builder, serviceCaps: ServiceHttpCapabilities): Task[Builder]
}

object AwsConfig {
  val default: ZLayer[HttpClient, Nothing, AwsConfig] = customized(
    ClientCustomization.None
  )

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
                        .map(zio.Duration.fromScala)
                    )(_.apiCallTimeout)
                    .optionallyWith(
                      commonClientConfig.apiCallAttemptTimeout
                        .map(zio.Duration.fromScala)
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
