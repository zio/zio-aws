package io.github.vigoo.zioaws.core

import io.github.vigoo.zioaws.core.httpclient.HttpClient
import software.amazon.awssdk.awscore.client.builder.{AwsAsyncClientBuilder, AwsClientBuilder, AwsDefaultClientBuilder}
import zio.{Has, Layer, Task, ULayer, ZIO, ZLayer}

package object config {
  type AwsConfig = Has[AwsConfig.Service]

  object AwsConfig {
    trait Service {
      def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Task[Builder]
      def configureHttpClient[Client, Builder <: AwsAsyncClientBuilder[Builder, Client]](builder: Builder): Task[Builder]
    }
  }

  val default: ZLayer[HttpClient, Nothing, AwsConfig] = ZLayer.fromService { httpClient =>
    new AwsConfig.Service {
      override def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Task[Builder] =
        ZIO.succeed(builder)

      override def configureHttpClient[Client, Builder <: AwsAsyncClientBuilder[Builder, Client]](builder: Builder): Task[Builder] =
        ZIO.succeed(builder.httpClient(httpClient.client))
    }
  }
}
