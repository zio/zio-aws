package io.github.vigoo.zioaws.netty

import io.github.vigoo.zioaws.core.httpclient.Protocol
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslProvider
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import zio.config.ConfigDescriptor._
import zio.config._
import zio._

package object descriptors {
  val httpOrHttps: ConfigDescriptor[HttpOrHttps] =
    string.transformOrFail(
      {
        case "http"  => Right(HttpOrHttps.Http)
        case "https" => Right(HttpOrHttps.Https)
        case other: String =>
          Left(s"Invalid value $other. Use 'http' or 'https'")
      },
      (value: HttpOrHttps) => Right(value.asString)
    )

  val proxyConfiguration: ConfigDescriptor[ProxyConfiguration] =
    (nested("scheme")(httpOrHttps)
      .default(HttpOrHttps.Http) ?? "The proxy scheme" |@|
      string("host") ?? "Hostname of the proxy" |@|
      int("port") ?? "Port of the proxy" |@|
      set("nonProxyHosts")(string)
        .default(Set.empty) ?? "Hosts that should not be proxied")
      .to[ProxyConfiguration]

  val http2Configuration: ConfigDescriptor[Http2Config] =
    (long(
      "maxStreams"
    ) ?? "Max number of concurrent streams per connection" |@|
      int("initialWindowSize") ?? "Initial window size of a stream" |@|
      zioDuration("healthCheckPingPeriod").default(
        5.seconds
      ) ?? "The period that the Netty client will send PING frames to the remote endpoint")
      .to[Http2Config]

  def channelOption[T, JT](
      opt: ChannelOption[JT],
      fromJava: JT => T,
      toJava: T => JT
  )(
      desc: ConfigDescriptor[T]
  ): ConfigDescriptor[Option[NettyOptionValue[JT]]] =
    nested(opt.name())(desc).optional.transform(
      _.map(value => NettyOptionValue(opt, toJava(value))),
      opt => opt.map(_.value).map(fromJava)
    )

  def boolChannelOption(
      opt: ChannelOption[java.lang.Boolean]
  ): ConfigDescriptor[Option[NettyOptionValue[java.lang.Boolean]]] =
    channelOption[Boolean, java.lang.Boolean](
      opt,
      (b: java.lang.Boolean) => b.booleanValue(),
      (b: Boolean) => java.lang.Boolean.valueOf(b)
    )(boolean)

  def intChannelOption(
      opt: ChannelOption[java.lang.Integer]
  ): ConfigDescriptor[Option[NettyOptionValue[java.lang.Integer]]] =
    channelOption[Int, java.lang.Integer](
      opt,
      (i: java.lang.Integer) => i.intValue(),
      (i: Int) => java.lang.Integer.valueOf(i)
    )(int)

  def durationMsChannelOption(
      opt: ChannelOption[java.lang.Integer]
  ): ConfigDescriptor[Option[NettyOptionValue[java.lang.Integer]]] =
    channelOption[Duration, java.lang.Integer](
      opt,
      (i: java.lang.Integer) => i.intValue().millis,
      (d: Duration) => java.lang.Integer.valueOf(d.toMillis.toInt)
    )(zioDuration)

  val nettyChannelOptions: ConfigDescriptor[NettyChannelOptions] = {
    val socketChannelOptions =
      io.github.vigoo.zioaws.core.httpclient.descriptors.channelOptions

    def findOpt[T](
        options: NettyChannelOptions,
        key: ChannelOption[_]
    ): Option[NettyOptionValue[T]] =
      options.options
        .find { opt =>
          opt.key == key
        }
        .asInstanceOf[Option[NettyOptionValue[T]]]

    import ChannelOption._
    val channelOptions =
      (durationMsChannelOption(
        CONNECT_TIMEOUT_MILLIS
      ) ?? "Connect timeout" |@|
        intChannelOption(WRITE_SPIN_COUNT) ?? "Write spin count" |@|
        boolChannelOption(ALLOW_HALF_CLOSURE) ?? "Allow half closure" |@|
        boolChannelOption(AUTO_READ) ?? "Auto read" |@|
        boolChannelOption(AUTO_CLOSE) ?? "Auto close" |@|
        boolChannelOption(
          SINGLE_EVENTEXECUTOR_PER_GROUP
        ) ?? "Single event executor per group").tupled.transform(
        tuple =>
          NettyChannelOptions(tuple.productIterator.collect {
            case Some(opt: NettyOptionValue[_]) => opt
          }.toVector),
        (channelOptions: NettyChannelOptions) =>
          (
            findOpt(channelOptions, CONNECT_TIMEOUT_MILLIS),
            findOpt(channelOptions, WRITE_SPIN_COUNT),
            findOpt(channelOptions, ALLOW_HALF_CLOSURE),
            findOpt(channelOptions, AUTO_READ),
            findOpt(channelOptions, AUTO_CLOSE),
            findOpt(channelOptions, SINGLE_EVENTEXECUTOR_PER_GROUP)
          )
      )

    (socketChannelOptions |@| channelOptions)(
      (opts1, opts2) => opts2.withSocketOptions(opts1),
      _ => None
    )
  }

  val protocol: ConfigDescriptor[Protocol] =
    string.transformOrFail(
      {
        case "HTTP/1.1" => Right(Protocol.Http11)
        case "HTTP/2"   => Right(Protocol.Http2)
        case "Dual"     => Right(Protocol.Dual)
        case other: String =>
          Left(
            s"Invalid protocol: '$other'. Use 'HTTP/1.1' or 'HTTP/2' or 'Dual'"
          )
      },
      {
        case Protocol.Http11 => Right("HTTP/1.1")
        case Protocol.Http2  => Right("HTTP/2")
        case Protocol.Dual   => Right("Dual")
      }
    )

  val sslProvider: ConfigDescriptor[SslProvider] =
    string.transformOrFail(
      {
        case "JDK"            => Right(SslProvider.JDK)
        case "OPENSSL"        => Right(SslProvider.OPENSSL)
        case "OPENSSL_REFCNT" => Right(SslProvider.OPENSSL_REFCNT)
        case other: String =>
          Left(
            s"Invalid SSL provider: '$other'. Use 'JDK', 'OPENSSL' or 'OPENSSL_REFCNT'"
          )
      },
      {
        case SslProvider.JDK            => Right("JDK")
        case SslProvider.OPENSSL        => Right("OPENSSL")
        case SslProvider.OPENSSL_REFCNT => Right("OPENSSL_REFCNT")
      }
    )

  val nettyClientConfig: ConfigDescriptor[NettyClientConfig] = {
    def globalDefault[T](key: SdkHttpConfigurationOption[T]): T =
      SdkHttpConfigurationOption.GLOBAL_HTTP_DEFAULTS.get(key)
    import SdkHttpConfigurationOption._
    (
      int("maxConcurrency").default(
        globalDefault[Integer](MAX_CONNECTIONS)
      ) ?? "Maximum number of allowed concurrent requests" |@|
        int("maxPendingConnectionAcquires").default(
          globalDefault[Integer](MAX_PENDING_CONNECTION_ACQUIRES)
        ) ?? "The maximum number of pending acquires allowed" |@|
        zioDuration("readTimeout").default(
          globalDefault(READ_TIMEOUT)
        ) ?? "The amount of time to wait for a read on a socket" |@|
        zioDuration("writeTimeout").default(
          globalDefault(WRITE_TIMEOUT)
        ) ?? "The amount of time to wait for a write on a socket" |@|
        zioDuration("connectionTimeout").default(
          globalDefault(CONNECTION_TIMEOUT)
        ) ?? "The amount of time to wait when initially establishing a connection before giving up" |@|
        zioDuration("connectionAcquisitionTimeout").default(
          globalDefault(CONNECTION_ACQUIRE_TIMEOUT)
        ) ?? "The amount of time to wait when acquiring a connection from the pool before giving up" |@|
        zioDuration("connectionTimeToLive").default(
          globalDefault(CONNECTION_TIME_TO_LIVE)
        ) ?? "The maximum amount of time that a connection should be allowed to remain open, regardless of usage frequency" |@|
        zioDuration("connectionMaxIdleTime").default(
          5.seconds
        ) ?? "Maximum amount of time that a connection should be allowed to remain open while idle" |@|
        boolean("useIdleConnectionReaper").default(
          globalDefault[java.lang.Boolean](REAP_IDLE_CONNECTIONS)
        ) ?? "If true, the idle connections in the pool should be closed" |@|
        nested("protocol")(protocol)
          .default(Protocol.Dual) ?? "HTTP/1.1 or HTTP/2 or Dual" |@|
        nested("channelOptions")(nettyChannelOptions).default(
          NettyChannelOptions(Vector.empty)
        ) ?? "Custom Netty channel options" |@|
        nested("sslProvider")(
          sslProvider
        ).optional ?? "The SSL provider to be used" |@|
        nested("proxy")(
          proxyConfiguration
        ).optional ?? "Proxy configuration" |@|
        nested("http2")(
          http2Configuration
        ).optional ?? "HTTP/2 specific options"
    ).to[NettyClientConfig]
  }
}
