package io.github.vigoo.zioaws.core

import software.amazon.awssdk.awscore.client.builder.{AwsAsyncClientBuilder, AwsClientBuilder, AwsDefaultClientBuilder}
import zio.{Has, Task, ULayer, ZIO, ZLayer}

package object config {
  type AwsConfig = Has[AwsConfig.Service]

  object AwsConfig {
    trait Service {
      def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Task[Builder]
      def configureHttpClient[Client, Builder <: AwsAsyncClientBuilder[Builder, Client]](builder: Builder): Task[Builder]
    }
  }

  val default: ULayer[AwsConfig] = ZLayer.succeed {
    new AwsConfig.Service {
      override def configure[Client, Builder <: AwsClientBuilder[Builder, Client]](builder: Builder): Task[Builder] =
        ZIO.succeed(builder)

      def configureHttpClient[Client, Builder <: AwsAsyncClientBuilder[Builder, Client]](builder: Builder): Task[Builder] =
        ZIO.succeed(builder)
    }
  }
}
