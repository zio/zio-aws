package zio.aws.netty

import zio.Duration

/**
 * HTTP/2 settings for the Netty client. `NettyHttpClient.configured` passes them to the AWS SDK's `Http2Configuration`.
 *
 * @param maxStreams the maximum number of concurrent streams on one HTTP/2 connection
 * @param initialWindowSize the initial flow-control window size of a stream, in bytes
 * @param healthCheckPingPeriod how often the client sends a `PING` frame to check that an HTTP/2 connection is alive
 */
case class Http2Config(
    maxStreams: Long,
    initialWindowSize: Int,
    healthCheckPingPeriod: Duration
)
