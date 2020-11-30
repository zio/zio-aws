package io.github.vigoo.zioaws.core

import java.net.{NetworkInterface, SocketOption, StandardSocketOptions}

import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.config.ConfigDescriptor._
import zio.config._
import zio._

import scala.util.Try
import io.github.vigoo.zioaws.core.httpclient.Protocol.Http11
import io.github.vigoo.zioaws.core.httpclient.Protocol.Http2
import io.github.vigoo.zioaws.core.httpclient.Protocol.Dual

package object httpclient {
  type HttpClient = Has[HttpClient.Service]

  object HttpClient {
    trait Service {
      def clientFor(
          serviceCaps: ServiceHttpCapabilities
      ): Task[SdkAsyncHttpClient]
    }
  }

  def clientFor(
      serviceCaps: ServiceHttpCapabilities
  ): ZIO[HttpClient.Service, Throwable, SdkAsyncHttpClient] =
    ZIO.accessM(_.clientFor(serviceCaps))

  sealed trait Protocol
  object Protocol {
    case object Http11 extends Protocol
    case object Http2 extends Protocol
    case object Dual extends Protocol
  }

  case class ServiceHttpCapabilities(supportsHttp2: Boolean)

  def fromManagedPerProtocol[R, E, A <: SdkAsyncHttpClient](
      http11Client: ZManaged[R, E, A],
      http2Client: ZManaged[R, E, A]
  )(protocol: Protocol): ZLayer[R, E, HttpClient] =
    ZLayer.fromManaged(
      fromManagedPerProtocolManaged(http11Client, http2Client)(protocol)
    )

  def fromManagedPerProtocolManaged[R, E, A <: SdkAsyncHttpClient](
      http11Client: ZManaged[R, E, A],
      http2Client: ZManaged[R, E, A]
  )(protocol: Protocol): ZManaged[R, E, HttpClient.Service] =
    protocol match {
      case Http11 =>
        http11Client.map { client =>
          new HttpClient.Service {
            override def clientFor(
                serviceCaps: ServiceHttpCapabilities
            ): Task[SdkAsyncHttpClient] =
              Task.succeed(client)
          }
        }
      case Http2 =>
        http2Client.map { client =>
          new HttpClient.Service {
            override def clientFor(
                serviceCaps: ServiceHttpCapabilities
            ): Task[SdkAsyncHttpClient] =
              if (serviceCaps.supportsHttp2) {
                Task.succeed(client)
              } else {
                Task.fail(
                  new UnsupportedOperationException(
                    "The http client only supports HTTP 2 but the client requires HTTP 1.1"
                  )
                )
              }
          }
        }
      case Dual =>
        for {
          http11 <- http11Client
          http2 <- http2Client
        } yield new HttpClient.Service {
          override def clientFor(
              serviceCaps: ServiceHttpCapabilities
          ): Task[SdkAsyncHttpClient] =
            if (serviceCaps.supportsHttp2) {
              Task.succeed(http11)
            } else {
              Task.succeed(http2)
            }
        }
    }

  case class OptionValue[T](key: SocketOption[T], value: T)
  case class ChannelOptions(options: Vector[OptionValue[Any]])

  object descriptors {

    def socketOption[T, JT](
        opt: SocketOption[JT],
        fromJava: JT => T,
        toJava: T => JT
    )(desc: ConfigDescriptor[T]): ConfigDescriptor[Option[OptionValue[JT]]] =
      nested(opt.name())(desc).optional.xmap(
        _.map(value => OptionValue(opt, toJava(value))),
        opt => opt.map(_.value).map(fromJava)
      )

    def boolSocketOption(
        opt: SocketOption[java.lang.Boolean]
    ): ConfigDescriptor[Option[OptionValue[java.lang.Boolean]]] =
      socketOption[Boolean, java.lang.Boolean](
        opt,
        (b: java.lang.Boolean) => b.booleanValue(),
        (b: Boolean) => java.lang.Boolean.valueOf(b)
      )(boolean)

    def intSocketOption(
        opt: SocketOption[java.lang.Integer]
    ): ConfigDescriptor[Option[OptionValue[java.lang.Integer]]] =
      socketOption[Int, java.lang.Integer](
        opt,
        (i: java.lang.Integer) => i.intValue(),
        (i: Int) => java.lang.Integer.valueOf(i)
      )(int)

    val networkInterfaceByName: ConfigDescriptor[NetworkInterface] =
      string.xmapEither(
        name =>
          Try(NetworkInterface.getByName(name)).toEither.left.map(_.getMessage),
        iface => Right(iface.getName)
      )

    val channelOptions: ConfigDescriptor[ChannelOptions] = {
      import StandardSocketOptions._

      def findOpt[T](
          options: ChannelOptions,
          key: SocketOption[_]
      ): Option[OptionValue[T]] =
        options.options
          .find { opt =>
            opt.key == key
          }
          .asInstanceOf[Option[OptionValue[T]]]

      (boolSocketOption(
        SO_BROADCAST
      ) ?? "Allow transmission of broadcast datagrams" |@|
        boolSocketOption(SO_KEEPALIVE) ?? "Keep connection alive" |@|
        intSocketOption(SO_SNDBUF) ?? "The size of the socket send buffer" |@|
        intSocketOption(
          SO_RCVBUF
        ) ?? "The size of the socket receive buffer" |@|
        boolSocketOption(SO_REUSEADDR) ?? "Re-use address" |@|
        intSocketOption(SO_LINGER) ?? "Linger on close if data is present" |@|
        intSocketOption(IP_TOS) ?? "The ToS octet in the IP header" |@|
        socketOption(
          IP_MULTICAST_IF,
          identity[NetworkInterface],
          identity[NetworkInterface]
        )(
          networkInterfaceByName
        ) ?? "The network interface's name for IP multicast datagrams" |@|
        intSocketOption(
          IP_MULTICAST_TTL
        ) ?? "The time-to-live for IP multicast datagrams" |@|
        boolSocketOption(
          IP_MULTICAST_LOOP
        ) ?? "Loopback for IP multicast datagrams" |@|
        boolSocketOption(TCP_NODELAY) ?? "Disable the Nagle algorithm").tupled
        .xmap(
          tuple =>
            ChannelOptions(tuple.productIterator.collect {
              case Some(opt: OptionValue[_]) =>
                opt.asInstanceOf[OptionValue[Any]]
            }.toVector),
          channelOptions =>
            (
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
  }
}
