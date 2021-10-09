package io.github.vigoo.zioaws.core.config

import software.amazon.awssdk.auth.credentials.{
  AnonymousCredentialsProvider,
  AwsBasicCredentials,
  AwsCredentials,
  AwsCredentialsProvider,
  DefaultCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import zio.config.ConfigDescriptor._
import zio.config._

package object descriptors {
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
          case _ => Left("Unsupported credentials provider")
        }
      )
    val anonymousCredentialsProvider: ConfigDescriptor[AwsCredentialsProvider] =
      string.transformOrFail(
        s =>
          if (s == "anonymous") Right(AnonymousCredentialsProvider.create())
          else Left("Not 'anonymous'"),
        {
          case _: AnonymousCredentialsProvider => Right("anonymous")
          case _ => Left("Unsupported credentials provider")
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
      string("defaultProfileName").optional ?? "Default profile name")
      .to[CommonClientConfig]

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
      ).optional ?? "Common settings for AWS service clients")
      .to[CommonAwsConfig]
}
