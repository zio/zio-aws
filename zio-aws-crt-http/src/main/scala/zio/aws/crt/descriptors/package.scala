package zio.aws.crt

import zio.Config

package object descriptors {
  val connectionHealthConfiguration: Config[ConnectionHealthConfiguration] =
    (
      (Config.long(
        "minimumThroughputInBps"
      ) ?? "Sets a throughput threshold for connections. Throughput below this value will be considered unhealthy") zip
        (Config.duration(
          "minimumThroughputTimeout"
        ) ?? "Sets how long a connection is allowed to be unhealthy before getting shut down")
    ).map { case (minimumThroughputInBps, minimumThroughputTimeout) =>
      ConnectionHealthConfiguration(
        minimumThroughputInBps,
        minimumThroughputTimeout
      )
    }

  val proxyConfiguration: Config[ProxyConfiguration] =
    (
      (Config.string("scheme") ?? "The scheme of the proxy") zip
        (Config.string("host") ?? "The host of the proxy") zip
        (Config.int("port") ?? "The port of the proxy") zip
        (Config.string("username") ?? "The username for the proxy") zip
        (Config.string("password") ?? "The password for the proxy")
    ).map { case (scheme, host, port, username, password) =>
      ProxyConfiguration(
        scheme,
        host,
        port,
        username,
        password
      )
    }

  val tcpKeepAliveConfiguration: Config[TcpKeepAliveConfiguration] =
    (
      (Config.duration(
        "keepAliveInterval"
      ) ?? "The number of seconds between TCP keepalive packets being sent to the peer") zip
        (Config.duration(
          "keepAliveTimeout"
        ) ?? "The number of seconds to wait for a keepalive response before considering the connection timed out")
    ).map { case (keepAliveInterval, keepAliveTimeout) =>
      TcpKeepAliveConfiguration(
        keepAliveInterval,
        keepAliveTimeout
      )
    }

  val awsCrtHttpClientConfig: Config[AwsCrtHttpClientConfig] =
    (
      (Config.int(
        "maxConcurrency"
      ) ?? "The Maximum number of allowed concurrent requests. For HTTP/1.1 this is the same as max connections.") zip
        (Config.long(
          "readBufferSizeInBytes"
        ) ?? "Configures the number of unread bytes that can be buffered in the client before we stop reading from the underlying TCP socket and wait for the Subscriber to read more data.") zip
        (proxyConfiguration.nested("proxy").optional
          ?? "Sets the http proxy configuration to use for this client.") zip
        (connectionHealthConfiguration.nested("connectionHealth").optional
          ?? "Configure the health checks for all connections established by this client.") zip
        (Config.duration(
          "connectionMaxIdleTime"
        ) ?? "Configure the maximum amount of time that a connection should be allowed to remain open while idle.") zip
        (Config.duration(
          "connectionTimeout"
        ) ?? "The amount of time to wait when initially establishing a connection before giving up and timing out.") zip
        // (Config.duration(
        //   "connectionAcquisitionTimeout"
        // ) ?? "The amount of time to wait when acquiring a connection from the pool before giving up and timing out.") zip
        (tcpKeepAliveConfiguration.nested("tcpKeepAlive").optional
          ?? "Configure whether to enable tcpKeepAlive and relevant configuration for all connections established by this client.") zip
        (Config.boolean(
          "postQuantumTlsEnabled"
        ) ?? "Configure whether to enable a hybrid post-quantum key exchange option for the Transport Layer Security (TLS) network encryption protocol when communicating with services that support Post Quantum TLS. If Post Quantum cipher suites are not supported on the platform, the SDK will use the default TLS cipher suites.")
    ).map {
      case (
            maxConcurrency,
            readBufferSizeInBytes,
            proxyConfiguration,
            connectionHealthConfiguration,
            connectionMaxIdleTime,
            connectionTimeout,
            // connectionAcquisitionTimeout,
            tcpKeepAliveConfiguration,
            postQuantumTlsEnabled
          ) =>
        AwsCrtHttpClientConfig(
          maxConcurrency,
          readBufferSizeInBytes,
          proxyConfiguration,
          connectionHealthConfiguration,
          connectionMaxIdleTime,
          connectionTimeout,
          // connectionAcquisitionTimeout,
          tcpKeepAliveConfiguration,
          postQuantumTlsEnabled
        )
    }
}
