package zio.aws.crt

import zio.Duration

final case class AwsCrtHttpClientConfig(
    maxConcurrency: Int,
    readBufferSizeInBytes: Long,
    proxyConfiguration: Option[ProxyConfiguration],
    connectionHealthConfiguration: Option[ConnectionHealthConfiguration],
    connectionMaxIdleTime: Duration,
    connectionTimeout: Duration,
    // connectionAcquisitionTimeout: Duration,
    tcpKeepAliveConfiguration: Option[TcpKeepAliveConfiguration],
    postQuantumTlsEnabled: Boolean
)
