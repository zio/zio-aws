package zio.aws.core.config

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region

import java.net.URI

/** The settings that `AwsConfig.configured()` applies to the client builder of every service. Build it in code, or read it with the `commonAwsConfig` descriptor in `zio.aws.core.config.descriptors`.
  *
  * @param region
  *   the AWS region; when `None`, the builder's region is left unchanged
  * @param endpointOverride
  *   an endpoint that replaces the AWS endpoint, for example a local emulator; when `None`, the builder's endpoint is left unchanged
  * @param commonClientConfig
  *   extra headers, timeouts and the profile name; when defined, the AWS SDK's own retries are turned off
  * @see
  *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
  */
case class CommonAwsConfig(
    region: Option[Region],
    credentialsProvider: AwsCredentialsProvider,
    endpointOverride: Option[URI],
    commonClientConfig: Option[CommonClientConfig]
)
