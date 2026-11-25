package zio.aws.core.config

import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder

/** Changes the AWS SDK client builder of every service that an `AwsConfig.customized` layer configures. Its one method is generic, so the same code works for the builders of all services.
  *
  * @see
  *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
  */
trait ClientCustomization {
  /** Returns the changed `builder`. */
  def customize[Client, Builder <: AwsClientBuilder[Builder, Client]](
      builder: Builder
  ): Builder
}

/** Predefined [[ClientCustomization]] values. */
object ClientCustomization {

  /** A customization that returns the builder unchanged. `AwsConfig.default` uses it. */
  object None extends ClientCustomization {
    override def customize[Client, Builder <: AwsClientBuilder[
      Builder,
      Client
    ]](builder: Builder): Builder = builder
  }

}
