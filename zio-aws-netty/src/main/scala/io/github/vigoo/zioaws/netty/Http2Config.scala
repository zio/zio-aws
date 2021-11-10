package io.github.vigoo.zioaws.netty

import zio.Duration

case class Http2Config(
    maxStreams: Long,
    initialWindowSize: Int,
    healthCheckPingPeriod: Duration
)
