package zio.aws.crt

import zio.Duration

final case class TcpKeepAliveConfiguration(
    keepAliveInterval: Duration,
    keepAliveTimeout: Duration
)
