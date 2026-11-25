package zio.aws.crt

import zio.Duration

/**
 * Settings for `AwsCrtHttpClient.configured`, which passes each field to the builder method of the same name on the AWS SDK's `AwsCrtAsyncHttpClient.Builder`. Read it from configuration with `descriptors.awsCrtHttpClientConfig`.
 *
 * @param maxConcurrency the maximum number of concurrent requests
 * @param readBufferSizeInBytes how many unread response bytes the client buffers before it stops reading from the socket
 * @param proxyConfiguration the proxy to send requests through; `None` connects directly
 * @param connectionHealthConfiguration the throughput check for connections; `None` keeps the SDK's default
 * @param connectionMaxIdleTime how long a connection can stay idle before the client closes it
 * @param connectionTimeout how long to wait for a new connection to be established
 * @param tcpKeepAliveConfiguration TCP keep-alive settings; `None` keeps the SDK's default
 * @param postQuantumTlsEnabled whether the client offers a hybrid post-quantum key exchange in TLS to services that support it
 */
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
