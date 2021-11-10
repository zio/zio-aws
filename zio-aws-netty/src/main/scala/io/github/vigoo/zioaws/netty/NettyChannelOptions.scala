package io.github.vigoo.zioaws.netty

import io.github.vigoo.zioaws.core.httpclient.ChannelOptions
import io.netty.channel.ChannelOption

case class NettyChannelOptions(options: Vector[NettyOptionValue[_]]) {
  def withSocketOptions(sockOptions: ChannelOptions): NettyChannelOptions =
    NettyChannelOptions(
      options ++ sockOptions.options.map(opt =>
        NettyOptionValue(ChannelOption.valueOf(opt.key.name()), opt.value)
      )
    )
}
