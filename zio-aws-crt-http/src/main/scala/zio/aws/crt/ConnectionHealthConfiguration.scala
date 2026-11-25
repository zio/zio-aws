package zio.aws.crt

import zio.Duration

/**
 * The throughput check for the connections of the CRT client, passed to the AWS SDK's CRT `ConnectionHealthConfiguration`.
 *
 * @param minimumThroughputInBps the minimum throughput, in bytes per second; a slower connection counts as unhealthy
 * @param minimumThroughputTimeout how long a connection may stay unhealthy before the client closes it
 */
final case class ConnectionHealthConfiguration(
    minimumThroughputInBps: Long,
    minimumThroughputTimeout: Duration
)
