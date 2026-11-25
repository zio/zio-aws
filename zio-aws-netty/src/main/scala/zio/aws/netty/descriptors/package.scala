package zio.aws.netty

import zio.aws.core.httpclient.Protocol
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslProvider
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import zio._

package object descriptors {

  /** Reads `http` or `https` as an [[zio.aws.netty.HttpOrHttps]]. Any other
    * string fails: `socks` gives `Invalid value socks. Use 'http' or 'https'`.
    */
  val httpOrHttps: Config[HttpOrHttps] =
    Config.string.mapOrFail(
      {
        case "http"        => Right(HttpOrHttps.Http)
        case "https"       => Right(HttpOrHttps.Https)
        case other: String =>
          Left(
            Config.Error.InvalidData(message =
              s"Invalid value $other. Use 'http' or 'https'"
            )
          )
      }
    )

  /** Reads a [[zio.aws.netty.ProxyConfiguration]] from the keys `scheme`
    * (default `http`), `host`, `port` and `nonProxyHosts` (a list of host
    * names, default empty).
    */
  val proxyConfiguration: Config[ProxyConfiguration] =
    (
      (httpOrHttps
        .nested("scheme")
        .withDefault(
          HttpOrHttps.Http
        ) ?? "The proxy scheme") zip
        (Config.string("host") ?? "Hostname of the proxy") zip
        (Config.int("port") ?? "Port of the proxy") zip
        (Config
          .setOf("nonProxyHosts", Config.string)
          .withDefault(
            Set.empty
          ) ?? "Hosts that should not be proxied")
    ).map { case (scheme, host, port, nonProxyHosts) =>
      ProxyConfiguration(scheme, host, port, nonProxyHosts)
    }

  /** Reads an [[zio.aws.netty.Http2Config]] from the keys `maxStreams` (an
    * `Int`), `initialWindowSize` and `healthCheckPingPeriod` (a duration,
    * default 5 seconds).
    */
  val http2Configuration: Config[Http2Config] =
    (
      (Config.int(
        "maxStreams"
      ) ?? "Max number of concurrent streams per connection") zip
        (Config.int(
          "initialWindowSize"
        ) ?? "Initial window size of a stream") zip
        (Config
          .duration("healthCheckPingPeriod")
          .withDefault(
            5.seconds
          ) ?? "The period that the Netty client will send PING frames to the remote endpoint")
    ).map { case (maxStreams, initialWindowSize, healthCheckPingPeriod) =>
      Http2Config(maxStreams, initialWindowSize, healthCheckPingPeriod)
    }

  /** Reads an optional Netty channel option with `desc` from the key named
    * after the option (`opt.name()`, for example `CONNECT_TIMEOUT_MILLIS`), and
    * converts the value with `toJava`. A missing key gives `None`.
    */
  def channelOption[T, JT](
      opt: ChannelOption[JT],
      toJava: T => JT
  )(
      desc: Config[T]
  ): Config[Option[NettyOptionValue[JT]]] =
    desc
      .nested(opt.name())
      .optional
      .map(
        _.map(value => NettyOptionValue(opt, toJava(value)))
      )

  /** A [[channelOption]] whose value is a `Boolean`. */
  def boolChannelOption(
      opt: ChannelOption[java.lang.Boolean]
  ): Config[Option[NettyOptionValue[java.lang.Boolean]]] =
    channelOption[Boolean, java.lang.Boolean](
      opt,
      (b: Boolean) => java.lang.Boolean.valueOf(b)
    )(Config.boolean)

  /** A [[channelOption]] whose value is an `Int`. */
  def intChannelOption(
      opt: ChannelOption[java.lang.Integer]
  ): Config[Option[NettyOptionValue[java.lang.Integer]]] =
    channelOption[Int, java.lang.Integer](
      opt,
      (i: Int) => java.lang.Integer.valueOf(i)
    )(Config.int)

  /** A [[channelOption]] that reads a duration and passes it to Netty as whole
    * milliseconds.
    */
  def durationMsChannelOption(
      opt: ChannelOption[java.lang.Integer]
  ): Config[Option[NettyOptionValue[java.lang.Integer]]] =
    channelOption[Duration, java.lang.Integer](
      opt,
      (d: Duration) => java.lang.Integer.valueOf(d.toMillis.toInt)
    )(Config.duration)

  /** Reads [[zio.aws.netty.NettyChannelOptions]] from these keys, each
    * optional: the socket options `SO_BROADCAST`, `SO_KEEPALIVE`, `SO_SNDBUF`,
    * `SO_RCVBUF`, `SO_REUSEADDR`, `SO_LINGER`, `IP_TOS`, `IP_MULTICAST_IF`,
    * `IP_MULTICAST_TTL`, `IP_MULTICAST_LOOP` and `TCP_NODELAY`, and the Netty
    * options `CONNECT_TIMEOUT_MILLIS` (a duration), `WRITE_SPIN_COUNT`,
    * `ALLOW_HALF_CLOSURE`, `AUTO_READ`, `AUTO_CLOSE` and
    * `SINGLE_EVENTEXECUTOR_PER_GROUP`.
    *
    * Netty does not apply `SO_BROADCAST`, `IP_MULTICAST_IF`, `IP_MULTICAST_TTL`
    * and `IP_MULTICAST_LOOP` to the NIO socket channel that the client of
    * `NettyHttpClient.configured` uses: it logs `Unknown channel option` and
    * skips them.
    */
  val nettyChannelOptions: Config[NettyChannelOptions] = {
    val socketChannelOptions =
      zio.aws.core.httpclient.descriptors.channelOptions

    import ChannelOption._
    val channelOptions =
      (
        (durationMsChannelOption(
          CONNECT_TIMEOUT_MILLIS
        ) ?? "Connect timeout") zip
          (intChannelOption(WRITE_SPIN_COUNT) ?? "Write spin count") zip
          (boolChannelOption(ALLOW_HALF_CLOSURE) ?? "Allow half closure") zip
          (boolChannelOption(AUTO_READ) ?? "Auto read") zip
          (boolChannelOption(AUTO_CLOSE) ?? "Auto close") zip
          (boolChannelOption(
            SINGLE_EVENTEXECUTOR_PER_GROUP
          ) ?? "Single event executor per group")
      ).map(tuple =>
        NettyChannelOptions(tuple.productIterator.collect {
          case Some(opt: NettyOptionValue[_]) => opt
        }.toVector)
      )

    (socketChannelOptions zip channelOptions).mapOrFail(
      { case (opts1, opts2) => Right(opts2.withSocketOptions(opts1)) }
    )
  }

  /** Reads `HTTP/1.1`, `HTTP/2` or `Dual` as a `Protocol`. Any other string
    * fails: `h2` gives
    * `Invalid protocol: 'h2'. Use 'HTTP/1.1' or 'HTTP/2' or 'Dual'`.
    */
  val protocol: Config[Protocol] =
    Config.string.mapOrFail(
      {
        case "HTTP/1.1"    => Right(Protocol.Http11)
        case "HTTP/2"      => Right(Protocol.Http2)
        case "Dual"        => Right(Protocol.Dual)
        case other: String =>
          Left(
            Config.Error.InvalidData(message =
              s"Invalid protocol: '$other'. Use 'HTTP/1.1' or 'HTTP/2' or 'Dual'"
            )
          )
      }
    )

  /** Reads `JDK`, `OPENSSL` or `OPENSSL_REFCNT` as a Netty `SslProvider`. Any
    * other string fails: `BORING` gives
    * `Invalid SSL provider: 'BORING'. Use 'JDK', 'OPENSSL' or 'OPENSSL_REFCNT'`.
    */
  val sslProvider: Config[SslProvider] =
    Config.string.mapOrFail(
      {
        case "JDK"            => Right(SslProvider.JDK)
        case "OPENSSL"        => Right(SslProvider.OPENSSL)
        case "OPENSSL_REFCNT" => Right(SslProvider.OPENSSL_REFCNT)
        case other: String    =>
          Left(
            Config.Error.InvalidData(message =
              s"Invalid SSL provider: '$other'. Use 'JDK', 'OPENSSL' or 'OPENSSL_REFCNT'"
            )
          )
      }
    )

  /** Reads a [[zio.aws.netty.NettyClientConfig]]. The keys and their defaults:
    *
    *   - `maxConcurrency`: 50
    *   - `maxPendingConnectionAcquires`: 10000
    *   - `readTimeout` and `writeTimeout`: 30 seconds
    *   - `connectionTimeout`: 2 seconds
    *   - `connectionAcquisitionTimeout`: 10 seconds
    *   - `connectionTimeToLive`: 0 (no limit)
    *   - `connectionMaxIdleTime`: 5 seconds
    *   - `useIdleConnectionReaper`: `true`
    *   - `protocol`: `Dual` (read with [[protocol]])
    *   - `channelOptions`: none (read with [[nettyChannelOptions]])
    *   - `sslProvider`, `proxy` and `http2`: not set (read with
    *     [[sslProvider]], [[proxyConfiguration]] and [[http2Configuration]])
    *
    * The defaults of `maxConcurrency`, `maxPendingConnectionAcquires`,
    * `readTimeout`, `writeTimeout`, `connectionTimeout`,
    * `connectionAcquisitionTimeout`, `connectionTimeToLive` and
    * `useIdleConnectionReaper` come from the SDK's
    * `SdkHttpConfigurationOption.GLOBAL_HTTP_DEFAULTS`.
    */
  val nettyClientConfig: Config[NettyClientConfig] = {
    def globalDefault[T](key: SdkHttpConfigurationOption[T]): T =
      SdkHttpConfigurationOption.GLOBAL_HTTP_DEFAULTS.get(key)
    import SdkHttpConfigurationOption._
    (
      (Config
        .int("maxConcurrency")
        .withDefault(
          globalDefault[Integer](MAX_CONNECTIONS).toInt
        ) ?? "Maximum number of allowed concurrent requests") zip
        (Config
          .int("maxPendingConnectionAcquires")
          .withDefault(
            globalDefault[Integer](MAX_PENDING_CONNECTION_ACQUIRES).toInt
          ) ?? "The maximum number of pending acquires allowed") zip
        (Config
          .duration("readTimeout")
          .withDefault(
            globalDefault(READ_TIMEOUT)
          ) ?? "The amount of time to wait for a read on a socket") zip
        (Config
          .duration("writeTimeout")
          .withDefault(
            globalDefault(WRITE_TIMEOUT)
          ) ?? "The amount of time to wait for a write on a socket") zip
        (Config
          .duration("connectionTimeout")
          .withDefault(
            globalDefault(CONNECTION_TIMEOUT)
          ) ?? "The amount of time to wait when initially establishing a connection before giving up") zip
        (Config
          .duration("connectionAcquisitionTimeout")
          .withDefault(
            globalDefault(CONNECTION_ACQUIRE_TIMEOUT)
          ) ?? "The amount of time to wait when acquiring a connection from the pool before giving up") zip
        (Config
          .duration("connectionTimeToLive")
          .withDefault(
            globalDefault(CONNECTION_TIME_TO_LIVE)
          ) ?? "The maximum amount of time that a connection should be allowed to remain open, regardless of usage frequency") zip
        (Config
          .duration("connectionMaxIdleTime")
          .withDefault(
            5.seconds
          ) ?? "Maximum amount of time that a connection should be allowed to remain open while idle") zip
        (Config
          .boolean("useIdleConnectionReaper")
          .withDefault(
            globalDefault[java.lang.Boolean](REAP_IDLE_CONNECTIONS)
              .booleanValue()
          ) ?? "If true, the idle connections in the pool should be closed") zip
        (protocol
          .nested("protocol")
          .withDefault(Protocol.Dual) ?? "HTTP/1.1 or HTTP/2 or Dual") zip
        (nettyChannelOptions
          .nested("channelOptions")
          .withDefault(
            NettyChannelOptions(Vector.empty)
          ) ?? "Custom Netty channel options") zip
        (sslProvider
          .nested("sslProvider")
          .optional ?? "The SSL provider to be used") zip
        (proxyConfiguration
          .nested("proxy")
          .optional ?? "Proxy configuration") zip
        (http2Configuration
          .nested("http2")
          .optional ?? "HTTP/2 specific options")
    ).map {
      case (
            maxConcurrency,
            maxPendingConnectionAcquires,
            readTimeout,
            writeTimeout,
            connectionTimeout,
            connectionAcquisitionTimeout,
            connectionTimeToLive,
            connectionMaxIdleTime,
            useIdleConnectionReaper,
            protocol,
            channelOptions,
            sslProvider,
            proxyConfiguration,
            http2Configuration
          ) =>
        NettyClientConfig(
          maxConcurrency,
          maxPendingConnectionAcquires,
          readTimeout,
          writeTimeout,
          connectionTimeout,
          connectionAcquisitionTimeout,
          connectionTimeToLive,
          connectionMaxIdleTime,
          useIdleConnectionReaper,
          protocol,
          channelOptions,
          sslProvider,
          proxyConfiguration,
          http2Configuration
        )
    }
  }
}
