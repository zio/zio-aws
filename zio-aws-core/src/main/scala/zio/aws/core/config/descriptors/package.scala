package zio.aws.core.config

import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.regions.Region
import zio.Config

import java.net.URI

package object descriptors {
  /** Reads an AWS region name, such as `eu-west-1`, with `Region.of`. Any non-blank name is accepted, including names that the AWS SDK doesn't list; a blank value fails with `region must not be blank or empty.` */
  val region: Config[Region] = Config.string.mapAttempt(Region.of)

  /** A URI config that accepts only URIs whose scheme is `http` or `https`, in any letter case. Any other value fails when the configuration is read. This includes values such as `minio:9000` or `localhost:9000`, which `java.net.URI` reads as a URI with the scheme `minio` or `localhost` and no host; the AWS SDK can't build a client with such an endpoint. For `localhost:9000` the error message is `Invalid endpoint URI 'localhost:9000': scheme must be 'http' or 'https' (e.g., 'http://localhost:9000')`. A value that is not a valid URI fails with the parse error of `Config.uri`.
    *
    * @see
    *   https://github.com/zio/zio-aws/issues/626
    */
  val httpUri: Config[URI] = Config.uri.mapOrFail { uri =>
    Option(uri.getScheme).map(_.toLowerCase) match {
      case Some("http") | Some("https") => Right(uri)
      case _                            =>
        Left(
          Config.Error.InvalidData(message =
            s"Invalid endpoint URI '$uri': scheme must be 'http' or 'https' (e.g., 'http://localhost:9000')"
          )
        )
    }
  }

  /** Reads static AWS credentials from the keys `accessKeyId` and `secretAccessKey`, with `AwsBasicCredentials.create`. */
  val awsCredentials: Config[AwsCredentials] =
    ((Config.string("accessKeyId") ?? "AWS access key ID") zip
      (Config.string("secretAccessKey") ?? "AWS secret access key")).mapAttempt(
      (AwsBasicCredentials.create _).tupled
    )

  /** Reads an AWS credentials provider. The key `type` selects it:
    *
    *  - `default`: `DefaultCredentialsProvider`;
    *  - `anonymous`: `AnonymousCredentialsProvider`;
    *  - `instance-profile`: `InstanceProfileCredentialsProvider`;
    *  - `static`: `StaticCredentialsProvider` with credentials that `awsCredentials` reads from the keys next to `type`.
    *
    * Any other type fails with `Invalid case:` followed by the type, for example `Invalid case: env`.
    */
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

  /** Reads one HTTP header from the keys `name` (a string) and `value` (a list of strings). */
  val rawHeader: Config[(String, List[String])] =
    (Config.string("name") ?? "Header name" zip
      Config.listOf("value", Config.string) ?? "Header value")

  /** Reads a list of `rawHeader` entries into a map from header name to values. When two entries have the same name, the last one wins. */
  val rawHeaderMap: Config[Map[String, List[String]]] =
    Config.listOf(rawHeader).map(_.toMap)

  /** Reads a [[CommonClientConfig]] from the keys `extraHeaders` (a list read by `rawHeaderMap`), `apiCallTimeout` and `apiCallAttemptTimeout` (optional durations, such as `PT5S` or `5 seconds`) and `defaultProfileName` (an optional string). `extraHeaders` is required: when it is absent, reading fails with missing data. */
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

  /** Reads a [[CommonAwsConfig]] from these keys:
    *
    *  - `region`: optional, read by `region`;
    *  - `credentials`: read by `credentialsProvider`; when the section is absent, `DefaultCredentialsProvider` is used, and an unknown `credentials.type` fails;
    *  - `endpointOverride`: optional, read by `httpUri`;
    *  - `client`: optional, read by `commonClientConfig`.
    *
    * Because `client` is optional and `client.extraHeaders` is required, a `client` section without `extraHeaders` reads as `None`, and its other keys, such as `client.apiCallTimeout`, have no effect.
    *
    * @see
    *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
    */
  val commonAwsConfig: Config[CommonAwsConfig] =
    (
      (region.nested("region").optional ?? "AWS region to connect to") zip
        (credentialsProvider
          .nested("credentials")
          .withDefault(
            DefaultCredentialsProvider.create()
          ) ?? "AWS credentials provider") zip
        (httpUri
          .nested("endpointOverride")
          .optional ?? "Overrides the AWS service endpoint (must include http:// or https:// scheme)") zip
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
