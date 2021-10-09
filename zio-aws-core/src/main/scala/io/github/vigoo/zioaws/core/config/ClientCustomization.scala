package io.github.vigoo.zioaws.core.config

import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder

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
