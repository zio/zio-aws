package io.github.vigoo.zioaws.netty

import io.github.vigoo.zioaws.core.httpclient.Protocol
import io.netty.handler.ssl.SslProvider
import zio.Duration

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
