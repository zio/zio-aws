package zio.aws.netty

import io.netty.channel.ChannelOption

/** One Netty channel option and the value to set it to. */
case class NettyOptionValue[T](key: ChannelOption[T], value: T)
