package zio.aws.core.config

import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.regions.Region
import zio.Config

package object descriptors {
  val region: Config[Region] = Config.string.mapAttempt(Region.of)

  val awsCredentials: Config[AwsCredentials] =
    ((Config.string("accessKeyId") ?? "AWS access key ID") zip
      (Config.string("secretAccessKey") ?? "AWS secret access key")).mapAttempt(
      (AwsBasicCredentials.create _).tupled
    )

  val credentialsProvider: Config[AwsCredentialsProvider] =
    Config
      .string("type")
      .switch(
        "default" -> Config.succeed(DefaultCredentialsProvider.create()),
        "anonymous" -> Config.succeed(AnonymousCredentialsProvider.create()),
        "instance-profile" -> Config
          .succeed(InstanceProfileCredentialsProvider.create()),
        "static" -> awsCredentials.map(StaticCredentialsProvider.create)
      )

  val rawHeader: Config[(String, List[String])] =
    (Config.string("name") ?? "Header name" zip
      Config.listOf("value", Config.string) ?? "Header value")

  val rawHeaderMap: Config[Map[String, List[String]]] =
    Config.listOf(rawHeader).map(_.toMap)

  val commonClientConfig: Config[CommonClientConfig] =
    (
      (rawHeaderMap.nested(
        "extraHeaders"
      ) ?? "Extra headers to be sent with each request") zip
        (Config
          .duration(
            "apiCallTimeout"
          )
          .optional ?? "Amount of time to allow the client to complete the execution of an API call") zip
        (Config
          .duration(
            "apiCallAttemptTimeout"
          )
          .optional ?? "Amount of time to wait for the HTTP request to complete before giving up") zip
        (Config.string("defaultProfileName").optional ?? "Default profile name")
    ).map {
      case (
            extraHeaders,
            apiCallTimeout,
            apiCallAttemptTimeout,
            defaultProfileName
          ) =>
        CommonClientConfig(
          extraHeaders,
          apiCallTimeout,
          apiCallAttemptTimeout,
          defaultProfileName
        )
    }

  val commonAwsConfig: Config[CommonAwsConfig] =
    (
      (region.nested("region").optional ?? "AWS region to connect to") zip
        (credentialsProvider
          .nested("credentials")
          .withDefault(
            DefaultCredentialsProvider.create()
          ) ?? "AWS credentials provider") zip
        (Config
          .uri(
            "endpointOverride"
          )
          .optional ?? "Overrides the AWS service endpoint") zip
        (commonClientConfig
          .nested("client")
          .optional ?? "Common settings for AWS service clients")
    ).map { case (region, credentials, endpointOverride, client) =>
      CommonAwsConfig(
        region,
        credentials,
        endpointOverride,
        client
      )
    }
}
