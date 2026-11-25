package zio.aws.crt

import zio.Duration

/**
 * TCP keep-alive settings for the CRT client, passed to the AWS SDK's CRT `TcpKeepAliveConfiguration`.
 *
 * @param keepAliveInterval the time between two keep-alive probes
 * @param keepAliveTimeout how long to wait for the answer to a keep-alive probe before the connection counts as timed out
 */
final case class TcpKeepAliveConfiguration(
    keepAliveInterval: Duration,
    keepAliveTimeout: Duration
)
