package zio.aws.core.config

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region

import java.net.URI

case class CommonAwsConfig(
    region: Option[Region],
    credentialsProvider: AwsCredentialsProvider,
    endpointOverride: Option[URI],
    commonClientConfig: Option[CommonClientConfig]
)
