package zio.aws.crt

import zio.Duration

final case class ConnectionHealthConfiguration(
    minimumThroughputInBps: Long,
    minimumThroughputTimeout: Duration
)
