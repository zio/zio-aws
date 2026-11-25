package zio.aws.core.config

import zio.Duration

/** Client settings that `AwsConfig.configured()` puts into the override configuration of every client builder, as part of a [[CommonAwsConfig]].
  *
  * @param extraHeaders
  *   headers sent with each request, as a header name and its values, passed to the SDK's `headers`
  * @param apiCallTimeout
  *   the time limit of a whole API call, passed to the SDK's `apiCallTimeout`
  * @param apiCallAttemptTimeout
  *   the time limit of one HTTP attempt, passed to the SDK's `apiCallAttemptTimeout`
  * @param defaultProfileName
  *   the profile name passed to the SDK's `defaultProfileName`
  * @see
  *   [[https://zio.dev/zio-aws/configuration/ Configuration]]
  */
case class CommonClientConfig(
    extraHeaders: Map[String, List[String]],
    apiCallTimeout: Option[Duration],
    apiCallAttemptTimeout: Option[Duration],
    defaultProfileName: Option[String]
)
