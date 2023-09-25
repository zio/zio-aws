package zio.aws.core.httpclient

import zio.Config

import java.net.{NetworkInterface, SocketOption, StandardSocketOptions}

package object descriptors {

  def socketOption[T, JT](
      opt: SocketOption[JT],
      toJava: T => JT
  )(desc: Config[T]): Config[Option[OptionValue[JT]]] =
    desc
      .nested(opt.name())
      .optional
      .map(
        _.map(value => OptionValue(opt, toJava(value)))
      )

  def boolSocketOption(
      opt: SocketOption[java.lang.Boolean]
  ): Config[Option[OptionValue[java.lang.Boolean]]] =
    socketOption[Boolean, java.lang.Boolean](
      opt,
      (b: Boolean) => java.lang.Boolean.valueOf(b)
    )(Config.boolean)

  def intSocketOption(
      opt: SocketOption[java.lang.Integer]
  ): Config[Option[OptionValue[java.lang.Integer]]] =
    socketOption[Int, java.lang.Integer](
      opt,
      (i: Int) => java.lang.Integer.valueOf(i)
    )(Config.int)

  val networkInterfaceByName: Config[NetworkInterface] =
    Config.string.mapAttempt(name => NetworkInterface.getByName(name))

  val channelOptions: Config[ChannelOptions] = {
    import StandardSocketOptions._

    (boolSocketOption(
      SO_BROADCAST
    ) ?? "Allow transmission of broadcast datagrams" zip
      boolSocketOption(SO_KEEPALIVE) ?? "Keep connection alive" zip
      intSocketOption(SO_SNDBUF) ?? "The size of the socket send buffer" zip
      intSocketOption(
        SO_RCVBUF
      ) ?? "The size of the socket receive buffer" zip
      boolSocketOption(SO_REUSEADDR) ?? "Re-use address" zip
      intSocketOption(SO_LINGER) ?? "Linger on close if data is present" zip
      intSocketOption(IP_TOS) ?? "The ToS octet in the IP header" zip
      socketOption(
        IP_MULTICAST_IF,
        identity[NetworkInterface]
      )(
        networkInterfaceByName
      ) ?? "The network interface's name for IP multicast datagrams" zip
      intSocketOption(
        IP_MULTICAST_TTL
      ) ?? "The time-to-live for IP multicast datagrams" zip
      boolSocketOption(
        IP_MULTICAST_LOOP
      ) ?? "Loopback for IP multicast datagrams" zip
      boolSocketOption(TCP_NODELAY) ?? "Disable the Nagle algorithm")
      .map(tuple =>
        ChannelOptions(tuple.productIterator.collect {
          case Some(opt: OptionValue[_]) =>
            opt.asInstanceOf[OptionValue[Any]]
        }.toVector)
      )
  }
}
