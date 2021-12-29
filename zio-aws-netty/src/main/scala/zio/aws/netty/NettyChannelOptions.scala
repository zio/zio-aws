package zio.aws.netty

import zio.aws.core.httpclient.ChannelOptions
import io.netty.channel.ChannelOption

case class NettyChannelOptions(options: Vector[NettyOptionValue[_]]) {
  def withSocketOptions(sockOptions: ChannelOptions): NettyChannelOptions =
    NettyChannelOptions(
      options ++ sockOptions.options.map(opt =>
        NettyOptionValue(ChannelOption.valueOf(opt.key.name()), opt.value)
      )
    )
}
