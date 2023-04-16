package zio.aws.core.config

import zio.Duration

case class CommonClientConfig(
    extraHeaders: Map[String, List[String]],
    apiCallTimeout: Option[Duration],
    apiCallAttemptTimeout: Option[Duration],
    defaultProfileName: Option[String]
)
