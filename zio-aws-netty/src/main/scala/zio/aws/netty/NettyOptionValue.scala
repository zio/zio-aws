package zio.aws.netty

import io.netty.channel.ChannelOption

case class NettyOptionValue[T](key: ChannelOption[T], value: T)
