package io.github.vigoo.zioaws.core

import java.net.URI

import io.github.vigoo.zioaws.core.httpclient.{
  HttpClient,
  ServiceHttpCapabilities
}
import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.awscore.client.builder.{
  AwsAsyncClientBuilder,
  AwsClientBuilder
}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.{RetryPolicy}
import software.amazon.awssdk.regions.Region
import zio.config.ConfigDescriptor._
import zio.config._
import zio.duration._
import zio.{Has, Task, ZIO, ZLayer}

import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

package object config {
  type AwsConfig = Has[AwsConfig.Service]

  object AwsConfig {

    trait Service {
      def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](
          builder: Builder
      ): Task[Builder]

      def configureHttpClient[
          Client,
          Builder <: AwsAsyncClientBuilder[Builder, Client]
      ](builder: Builder, serviceCaps: ServiceHttpCapabilities): Task[Builder]
    }

  }

  val default: ZLayer[HttpClient, Nothing, AwsConfig] = customized(
    ClientCustomization.None
  )

  trait ClientCustomization {
    def customize[Client, Builder <: AwsClientBuilder[Builder, Client]](
        builder: Builder
    ): Builder
  }

  object ClientCustomization {

    object None extends ClientCustomization {
      override def customize[Client, Builder <: AwsClientBuilder[
        Builder,
        Client
      ]](builder: Builder): Builder = builder
    }

  }

  def customized(
      customization: ClientCustomization
  ): ZLayer[HttpClient, Nothing, AwsConfig] =
    ZLayer.fromService { httpClient =>
      new AwsConfig.Service {
        override def configure[Client, Builder <: AwsClientBuilder[
          Builder,
          Client
        ]](builder: Builder): Task[Builder] =
          Task(customization.customize[Client, Builder](builder))

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

  case class CommonClientConfig(
      extraHeaders: Map[String, List[String]],
      apiCallTimeout: Option[Duration],
      apiCallAttemptTimeout: Option[Duration],
      defaultProfileName: Option[String]
  )

  case class CommonAwsConfig(
      region: Option[Region],
      credentialsProvider: AwsCredentialsProvider,
      endpointOverride: Option[URI],
      commonClientConfig: Option[CommonClientConfig]
  )

  def configured()
      : ZLayer[HttpClient with Has[CommonAwsConfig], Nothing, AwsConfig] =
    ZLayer
      .fromServices[HttpClient.Service, CommonAwsConfig, AwsConfig.Service] {
        (httpClient, commonConfig) =>
          new AwsConfig.Service {
            override def configure[Client, Builder <: AwsClientBuilder[
              Builder,
              Client
            ]](builder: Builder): Task[Builder] = {
              val builderHelper: BuilderHelper[Client] = BuilderHelper.apply
              import builderHelper._
              Task {
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
                          commonClientConfig.extraHeaders
                            .map { case (key, value) => key -> value.asJava }
                            .toMap
                            .asJava
                        )
                        .retryPolicy(RetryPolicy.none())
                        .optionallyWith(
                          commonClientConfig.apiCallTimeout
                            .map(zio.duration.Duration.fromScala)
                        )(_.apiCallTimeout)
                        .optionallyWith(
                          commonClientConfig.apiCallAttemptTimeout
                            .map(zio.duration.Duration.fromScala)
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

  object descriptors {
    val region: ConfigDescriptor[Region] = string.transform(Region.of, _.id())

    val awsCredentials: ConfigDescriptor[AwsCredentials] =
      (string("accessKeyId") ?? "AWS access key ID" |@|
        string("secretAccessKey") ?? "AWS secret access key")(
        AwsBasicCredentials.create,
        (creds: AwsCredentials) =>
          Some((creds.accessKeyId(), creds.secretAccessKey()))
      )

    val credentialsProvider: ConfigDescriptor[AwsCredentialsProvider] = {
      val defaultCredentialsProvider: ConfigDescriptor[AwsCredentialsProvider] =
        string.transformOrFail(
          s =>
            if (s == "default") Right(DefaultCredentialsProvider.create())
            else Left("Not 'default'"),
          {
            case _: DefaultCredentialsProvider => Right("default")
            case _                             => Left("Unsupported credentials provider")
          }
        )
      val anonymousCredentialsProvider
          : ConfigDescriptor[AwsCredentialsProvider] = string.transformOrFail(
        s =>
          if (s == "anonymous") Right(AnonymousCredentialsProvider.create())
          else Left("Not 'anonymous'"),
        {
          case _: AnonymousCredentialsProvider => Right("anonymous")
          case _                               => Left("Unsupported credentials provider")
        }
      )
      val staticCredentialsProvider: ConfigDescriptor[AwsCredentialsProvider] =
        awsCredentials.transform(
          creds => StaticCredentialsProvider.create(creds),
          _.resolveCredentials()
        )

      defaultCredentialsProvider <> anonymousCredentialsProvider <> staticCredentialsProvider
    }

    val rawHeader: ConfigDescriptor[(String, List[String])] =
      (string("name") ?? "Header name" |@|
        listOrSingleton("value")(string ?? "Header value")).tupled

    val rawHeaderMap: ConfigDescriptor[Map[String, List[String]]] =
      list(rawHeader).transform(
        _.toMap,
        _.toList
      )

    val commonClientConfig: ConfigDescriptor[CommonClientConfig] =
      (nested("extraHeaders")(
        rawHeaderMap
      ) ?? "Extra headers to be sent with each request" |@|
        duration(
          "apiCallTimeout"
        ).optional ?? "Amount of time to allow the client to complete the execution of an API call" |@|
        duration(
          "apiCallAttemptTimeout"
        ).optional ?? "Amount of time to wait for the HTTP request to complete before giving up" |@|
        string("defaultProfileName").optional ?? "Default profile name").to[CommonClientConfig]

    val commonAwsConfig: ConfigDescriptor[CommonAwsConfig] =
      (nested("region")(region).optional ?? "AWS region to connect to" |@|
        nested("credentials")(credentialsProvider).default(
          DefaultCredentialsProvider.create()
        ) ?? "AWS credentials provider" |@|
        uri(
          "endpointOverride"
        ).optional ?? "Overrides the AWS service endpoint" |@|
        nested("client")(
          commonClientConfig
        ).optional ?? "Common settings for AWS service clients").to[CommonAwsConfig]
  }

}
