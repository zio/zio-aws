package zio.aws.netty

import zio.aws.core.httpclient.Protocol
import io.netty.handler.ssl.SslProvider
import zio.Duration

/** Settings for `NettyHttpClient.configured`. `configured` passes the fields to
  * the matching methods of the AWS SDK's `NettyNioAsyncHttpClient.Builder`.
  * Read it from configuration with `descriptors.nettyClientConfig`, which also
  * gives the default values.
  *
  * @param maxConcurrency
  *   the maximum number of concurrent requests
  * @param maxPendingConnectionAcquires
  *   the maximum number of requests that can wait for a connection from the
  *   pool
  * @param connectionAcquisitionTimeout
  *   how long a request waits for a connection from the pool before it fails
  * @param connectionTimeToLive
  *   the time after which the client closes a connection that is not in use,
  *   however often it was used; zero means no limit
  * @param connectionMaxIdleTime
  *   how long a connection can stay idle before the idle connection reaper
  *   closes it
  * @param useIdleConnectionReaper
  *   whether the client closes connections that stay idle longer than
  *   `connectionMaxIdleTime`
  * @param protocol
  *   which Netty clients `configured` builds and how it picks one for a
  *   service, as in `NettyHttpClient.customized`
  * @param channelOptions
  *   Netty channel options set on each connection
  * @param sslProvider
  *   the TLS implementation; `None` keeps the SDK's default
  * @param proxyConfiguration
  *   the proxy to send requests through; `None` connects directly
  * @param http2
  *   HTTP/2 settings; `None` keeps the SDK's defaults
  */
case class NettyClientConfig(
    maxConcurrency: Int,
    maxPendingConnectionAcquires: Int,
    readTimeout: Duration,
    writeTimeout: Duration,
    connectionTimeout: Duration,
    connectionAcquisitionTimeout: Duration,
    connectionTimeToLive: Duration,
    connectionMaxIdleTime: Duration,
    useIdleConnectionReaper: Boolean,
    protocol: Protocol,
    channelOptions: NettyChannelOptions,
    sslProvider: Option[SslProvider],
    proxyConfiguration: Option[ProxyConfiguration],
    http2: Option[Http2Config]
)
