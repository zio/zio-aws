package io.github.vigoo.zioaws.core

import io.github.vigoo.zioaws.core.httpclient.HttpClient
import software.amazon.awssdk.awscore.client.builder.{AwsAsyncClientBuilder, AwsClientBuilder}
import zio.{Has, Task, ZIO, ZLayer}

package object config {
  type AwsConfig = Has[AwsConfig.Service]

  object AwsConfig {
    trait Service {
      def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Task[Builder]
      def configureHttpClient[Client, Builder <: AwsAsyncClientBuilder[Builder, Client]](builder: Builder): Task[Builder]
    }
  }

  val default: ZLayer[HttpClient, Nothing, AwsConfig] = customized(ClientCustomization.None)

  trait ClientCustomization {
    def customize[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Builder
  }

  object ClientCustomization {
    object None extends ClientCustomization {
      override def customize[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Builder = builder
    }
  }

  def customized(customization: ClientCustomization): ZLayer[HttpClient, Nothing, AwsConfig] = ZLayer.fromService { httpClient =>
    new AwsConfig.Service {
      override def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Task[Builder] =
        Task(customization.customize[Client, Builder](builder))

      override def configureHttpClient[Client, Builder <: AwsAsyncClientBuilder[Builder, Client]](builder: Builder): Task[Builder] =
        ZIO.succeed(builder.httpClient(httpClient.client))
    }
  }
}
