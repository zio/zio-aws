package io.github.vigoo.zioaws

import java.net.{NetworkInterface, SocketOption, StandardSocketOptions}
import java.nio.channels.AsynchronousChannelGroup

import io.github.vigoo.zioaws.core.BuilderHelper
import io.github.vigoo.zioaws.core.httpclient.HttpClient
import javax.net.ssl.SSLContext
import org.http4s.blaze.channel.{ChannelOptions, OptionValue}
import org.http4s.client.blaze.{BlazeClientBuilder, ParserMode}
import org.http4s.client.{RequestKey, defaults}
import org.http4s.headers.{AgentProduct, `User-Agent`}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.config.ConfigDescriptor._
import zio.config._
import zio.{Runtime, Task, ZIO, ZLayer}

import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

package object http4s {
  def customized(f: BlazeClientBuilder[Task] => BlazeClientBuilder[Task]): ZLayer[Any, Throwable, HttpClient] = {
    ZIO.runtime.toManaged_.flatMap { implicit runtime: Runtime[Any] =>
      Http4sClient.Http4sClientBuilder(f)
        .toManaged
        .map { c =>
          new HttpClient.Service {
            override val client: SdkAsyncHttpClient = c
          }
        }
    }.toLayer
  }

  def client(): ZLayer[Any, Throwable, HttpClient] = customized(identity)

  case class BlazeClientConfig(responseHeaderTimeout: Duration,
                               idleTimeout: Duration,
                               requestTimeout: Duration,
                               connectTimeout: Duration,
                               userAgent: `User-Agent`,
                               maxTotalConnections: Int,
                               maxWaitQueueLimit: Int,
                               checkEndpointIdentification: Boolean,
                               maxResponseLineSize: Int,
                               maxHeaderLength: Int,
                               maxChunkSize: Int,
                               chunkBufferMaxSize: Int,
                               parserMode: ParserMode,
                               bufferSize: Int,
                               channelOptions: ChannelOptions)

  val userAgent: ConfigDescriptor[`User-Agent`] =
    string.xmapEither(
      s => `User-Agent`.parse(s).left.map(_.message),
      h => Right(h.toString())
    )
  val parserMode: ConfigDescriptor[ParserMode] =
    string.xmapEither(
      {
        case "strict" => Right(ParserMode.Strict)
        case "lenient" => Right(ParserMode.Lenient)
        case s: String => Left(s"Invalid parser mode '$s'. Use 'strict' or 'lenient'")
      },
      {
        case ParserMode.Strict => Right("strict")
        case ParserMode.Lenient => Right("lenient")
      }
    )

  def socketOption[T, JT](opt: SocketOption[JT], fromJava: JT => T, toJava: T => JT)(desc: ConfigDescriptor[T]): ConfigDescriptor[Option[OptionValue[JT]]] =
    nested(opt.name())(desc).optional.xmap(_.map(value => OptionValue(opt, toJava(value))), opt => opt.map(_.value).map(fromJava))

  def boolSocketOption(opt: SocketOption[java.lang.Boolean]): ConfigDescriptor[Option[OptionValue[java.lang.Boolean]]] =
    socketOption[Boolean, java.lang.Boolean](opt, (b: java.lang.Boolean) => b.booleanValue(), (b: Boolean) => java.lang.Boolean.valueOf(b))(boolean)

  def intSocketOption(opt: SocketOption[java.lang.Integer]): ConfigDescriptor[Option[OptionValue[java.lang.Integer]]] =
    socketOption[Int, java.lang.Integer](opt, (i: java.lang.Integer) => i.intValue(), (i: Int) => java.lang.Integer.valueOf(i))(int)

  val networkInterfaceByName: ConfigDescriptor[NetworkInterface] =
    string.xmapEither(
      name => Try(NetworkInterface.getByName(name)).toEither.left.map(_.getMessage),
      iface => Right(iface.getName)
    )

  val channelOptions: ConfigDescriptor[ChannelOptions] = {
    import StandardSocketOptions._

    def findOpt[T](options: ChannelOptions, key: SocketOption[_]): Option[OptionValue[T]] =
      options.options.find { opt =>
        opt.key == key
      }.asInstanceOf[Option[OptionValue[T]]]

    (boolSocketOption(SO_BROADCAST) ?? "Allow transmission of broadcast datagrams" |@|
      boolSocketOption(SO_KEEPALIVE) ?? "Keep connection alive" |@|
      intSocketOption(SO_SNDBUF) ?? "The size of the socket send buffer" |@|
      intSocketOption(SO_RCVBUF) ?? "The size of the socket receive buffer" |@|
      boolSocketOption(SO_REUSEADDR) ?? "Re-use address" |@|
      intSocketOption(SO_LINGER) ?? "Linger on close if data is present" |@|
      intSocketOption(IP_TOS) ?? "The ToS octet in the IP header" |@|
      socketOption(IP_MULTICAST_IF, identity[NetworkInterface], identity[NetworkInterface])(networkInterfaceByName) ?? "The network interface's name for IP multicast datagrams" |@|
      intSocketOption(IP_MULTICAST_TTL) ?? "The time-to-live for IP multicast datagrams" |@|
      boolSocketOption(IP_MULTICAST_LOOP) ?? "Loopback for IP multicast datagrams" |@|
      boolSocketOption(TCP_NODELAY) ?? "Disable the Nagle algorithm"
      ).tupled.xmap(
      tuple => ChannelOptions(
        tuple.productIterator.collect {
          case Some(opt: OptionValue[_]) => opt
        }.toVector),
      channelOptions => (
        findOpt(channelOptions, SO_BROADCAST),
        findOpt(channelOptions, SO_KEEPALIVE),
        findOpt(channelOptions, SO_SNDBUF),
        findOpt(channelOptions, SO_RCVBUF),
        findOpt(channelOptions, SO_REUSEADDR),
        findOpt(channelOptions, SO_LINGER),
        findOpt(channelOptions, IP_TOS),
        findOpt(channelOptions, IP_MULTICAST_IF),
        findOpt(channelOptions, IP_MULTICAST_TTL),
        findOpt(channelOptions, IP_MULTICAST_LOOP),
        findOpt(channelOptions, TCP_NODELAY)
      )
    )
  }

  val blazeClientConfig: ConfigDescriptor[BlazeClientConfig] = (
    duration("responseHeaderTimeout").default(Duration.Inf) ?? "Timeout for receiving the header part of the response" |@|
      duration("idleTimeout").default(1.minute) ?? "Timeout for client connection staying idle" |@|
      duration("requestTimeout").default(defaults.RequestTimeout) ?? "Timeout for the whole request" |@|
      duration("connectTimeout").default(defaults.ConnectTimeout) ?? "Timeout for connecting to the server" |@|
      nested("userAgent")(userAgent).default(`User-Agent`(AgentProduct("http4s-blaze", Some(BuildInfo.version)))) ?? "User-Agent header sent by the client" |@|
      int("maxTotalConnections").default(10) ?? "Maximum number of parallel connections" |@|
      int("maxWaitQueueLimit").default(256) ?? "Maximum number of requests in queue" |@|
      boolean("checkEndpointIdentification").default(true) ?? "Check https identity" |@|
      int("maxResponseLineSize").default(4096) ?? "Maximum line length of headers in response" |@|
      int("maxHeaderLength").default(40960) ?? "Maximum total length of the response headers" |@|
      int("maxChunkSize").default(Int.MaxValue) ?? "Maximum chunk size" |@|
      int("chunkBufferMaxSize").default(1024 * 1024) ?? "Maximum size of the chunk buffer" |@|
      nested("parserMode")(parserMode).default(ParserMode.Strict) ?? "Parser mode, strict or lenient" |@|
      int("bufferSize").default(8192) ?? "Buffer size" |@|
      nested("channelOptions")(channelOptions).default(ChannelOptions(Vector.empty)) ?? "Collection of socket options"
    ) (BlazeClientConfig.apply, BlazeClientConfig.unapply)

  private def tryDefaultSslContext: Option[SSLContext] =
    try Some(SSLContext.getDefault)
    catch {
      case NonFatal(_) => None
    }

  def configured(sslContext: Option[SSLContext] = tryDefaultSslContext,
                 maxConnectionsPerRequestKey: Option[RequestKey => Int] = None,
                 asynchronousChannelGroup: Option[AsynchronousChannelGroup] = None,
                 additionalSocketOptions: Seq[OptionValue[_]] = Seq.empty): ZLayer[ZConfig[BlazeClientConfig], Throwable, HttpClient] = {
    ZLayer.fromServiceManaged { config =>
      ZIO.runtime.toManaged_.flatMap { implicit runtime: Runtime[Any] =>
        Http4sClient.Http4sClientBuilder(
          _.withResponseHeaderTimeout(config.responseHeaderTimeout)
            .withIdleTimeout(config.idleTimeout)
            .withRequestTimeout(config.requestTimeout)
            .withConnectTimeout(config.connectTimeout)
            .withUserAgent(config.userAgent)
            .withMaxTotalConnections(config.maxTotalConnections)
            .withMaxWaitQueueLimit(config.maxWaitQueueLimit)
            .withCheckEndpointAuthentication(config.checkEndpointIdentification)
            .withMaxResponseLineSize(config.maxResponseLineSize)
            .withMaxHeaderLength(config.maxHeaderLength)
            .withMaxChunkSize(config.maxChunkSize)
            .withChunkBufferMaxSize(config.chunkBufferMaxSize)
            .withParserMode(config.parserMode)
            .withBufferSize(config.bufferSize)
            .withChannelOptions(ChannelOptions(config.channelOptions.options ++ additionalSocketOptions))
            .withSslContextOption(sslContext)
            .withAsynchronousChannelGroupOption(asynchronousChannelGroup)
            .withMaxConnectionsPerRequestKey(maxConnectionsPerRequestKey.getOrElse(Function.const(config.maxWaitQueueLimit)))
        )
          .toManaged
          .map { c =>
            new HttpClient.Service {
              override val client: SdkAsyncHttpClient = c
            }
          }
      }
    }
  }
}
