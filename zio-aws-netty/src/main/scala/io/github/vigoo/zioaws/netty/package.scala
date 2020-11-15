package io.github.vigoo.zioaws

import io.github.vigoo.zioaws.core.BuilderHelper
import io.github.vigoo.zioaws.core.httpclient.{ChannelOptions, HttpClient}
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslProvider
import software.amazon.awssdk.http.{
  Protocol,
  SdkHttpConfigurationOption,
  TlsKeyManagersProvider,
  TlsTrustManagersProvider
}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.{
  Http2Configuration,
  NettyNioAsyncHttpClient,
  ProxyConfiguration => AwsProxyConfiguration
}
import zio.config._
import zio.config.ConfigDescriptor._
import zio.duration._
import zio.{ZIO, ZLayer, ZManaged}

import scala.jdk.CollectionConverters._

package object netty {
  val default: ZLayer[Any, Throwable, HttpClient] = customized(identity)

  def customized(
      customization: NettyNioAsyncHttpClient.Builder => NettyNioAsyncHttpClient.Builder =
        identity
  ): ZLayer[Any, Throwable, HttpClient] = {
    ZManaged
      .fromAutoCloseable(
        ZIO.effect(
          customization(
            NettyNioAsyncHttpClient
              .builder()
          ).build()
        )
      )
      .map { nettyClient =>
        new HttpClient.Service {
          override val client: SdkAsyncHttpClient = nettyClient
        }
      }
      .toLayer
  }

  sealed trait HttpOrHttps {
    val asString: String
  }

  object HttpOrHttps {

    case object Http extends HttpOrHttps {
      override val asString: String = "http"
    }

    case object Https extends HttpOrHttps {
      override val asString: String = "https"
    }

  }

  case class ProxyConfiguration(
      scheme: HttpOrHttps,
      host: String,
      port: Int,
      nonProxyHosts: Set[String]
  )

  case class Http2Config(
      maxStreams: Long,
      initialWindowSize: Int,
      healthCheckPingPeriod: Duration
  )

  case class NettyOptionValue[T](key: ChannelOption[T], value: T)

  case class NettyChannelOptions(options: Vector[NettyOptionValue[_]]) {
    def withSocketOptions(sockOptions: ChannelOptions): NettyChannelOptions =
      NettyChannelOptions(
        options ++ sockOptions.options.map(opt =>
          NettyOptionValue(ChannelOption.valueOf(opt.key.name()), opt.value)
        )
      )
  }

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

  def configured(
      tlsKeyManagersProvider: Option[TlsKeyManagersProvider] = None,
      tlsTrustManagersProvider: Option[TlsTrustManagersProvider] = None
  ): ZLayer[ZConfig[NettyClientConfig], Throwable, HttpClient] = {
    ZLayer.fromServiceManaged { config =>
      ZManaged
        .fromAutoCloseable(ZIO.effect {
          val builderHelper: BuilderHelper[NettyNioAsyncHttpClient] =
            BuilderHelper.apply
          import builderHelper._

          val builder0: NettyNioAsyncHttpClient.Builder =
            NettyNioAsyncHttpClient
              .builder()
              .maxConcurrency(config.maxConcurrency)
              .maxPendingConnectionAcquires(config.maxPendingConnectionAcquires)
              .readTimeout(config.readTimeout)
              .writeTimeout(config.writeTimeout)
              .connectionTimeout(config.connectionTimeout)
              .connectionAcquisitionTimeout(config.connectionAcquisitionTimeout)
              .connectionTimeToLive(config.connectionTimeToLive)
              .connectionMaxIdleTime(config.connectionMaxIdleTime)
              .useIdleConnectionReaper(config.useIdleConnectionReaper)
              .protocol(config.protocol)
              .optionallyWith(config.sslProvider)(_.sslProvider)
              .optionallyWith(config.proxyConfiguration)(builder =>
                proxy =>
                  builder.proxyConfiguration(
                    AwsProxyConfiguration
                      .builder()
                      .host(proxy.host)
                      .port(proxy.port)
                      .scheme(proxy.scheme.asString)
                      .nonProxyHosts(proxy.nonProxyHosts.asJava)
                      .build()
                  )
              )
              .optionallyWith(config.http2)(builder =>
                http2 =>
                  builder.http2Configuration(
                    Http2Configuration
                      .builder()
                      .healthCheckPingPeriod(http2.healthCheckPingPeriod)
                      .maxStreams(http2.maxStreams)
                      .initialWindowSize(http2.initialWindowSize)
                      .build()
                  )
              )
              .optionallyWith(tlsKeyManagersProvider)(_.tlsKeyManagersProvider)
              .optionallyWith(tlsTrustManagersProvider)(
                _.tlsTrustManagersProvider
              )

          val builder1 =
            config.channelOptions.options.foldLeft(builder0) { case (b, opt) =>
              b.putChannelOption(opt.key, opt.value)
            }

          builder1.build()
        })
        .map { nettyClient =>
          new HttpClient.Service {
            override val client: SdkAsyncHttpClient = nettyClient
          }
        }
    }
  }

  object descriptors {
    val httpOrHttps: ConfigDescriptor[HttpOrHttps] =
      string.xmapEither(
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
          .default(Set.empty) ?? "Hosts that should not be proxied")(
        ProxyConfiguration.apply,
        ProxyConfiguration.unapply
      )

    val http2Configuration: ConfigDescriptor[Http2Config] =
      (long(
        "maxStreams"
      ) ?? "Max number of concurrent streams per connection" |@|
        int("initialWindowSize") ?? "Initial window size of a stream" |@|
        zioDuration("healthCheckPingPeriod").default(
          5.seconds
        ) ?? "The period that the Netty client will send PING frames to the remote endpoint")(
        Http2Config.apply,
        Http2Config.unapply
      )

    def channelOption[T, JT](
        opt: ChannelOption[JT],
        fromJava: JT => T,
        toJava: T => JT
    )(
        desc: ConfigDescriptor[T]
    ): ConfigDescriptor[Option[NettyOptionValue[JT]]] =
      nested(opt.name())(desc).optional.xmap(
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
          ) ?? "Single event executor per group").tupled.xmap(
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
      string.xmapEither(
        {
          case "HTTP/1.1" => Right(Protocol.HTTP1_1)
          case "HTTP/2"   => Right(Protocol.HTTP2)
          case other: String =>
            Left(s"Invalid protocol: '$other'. Use 'HTTP/1.1' or 'HTTP/2'")
        },
        {
          case Protocol.HTTP1_1 => Right("HTTP/1.1")
          case Protocol.HTTP2   => Right("HTTP/2")
        }
      )

    val sslProvider: ConfigDescriptor[SslProvider] =
      string.xmapEither(
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
          globalDefault(MAX_CONNECTIONS)
        ) ?? "Maximum number of allowed concurrent requests" |@|
          int("maxPendingConnectionAcquires").default(
            globalDefault(MAX_PENDING_CONNECTION_ACQUIRES)
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
            globalDefault(REAP_IDLE_CONNECTIONS)
          ) ?? "If true, the idle connections in the pool should be closed" |@|
          nested("protocol")(protocol)
            .default(globalDefault(PROTOCOL)) ?? "HTTP/1.1 or HTTP/2" |@|
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
      )(NettyClientConfig.apply, NettyClientConfig.unapply)
    }
  }

}
