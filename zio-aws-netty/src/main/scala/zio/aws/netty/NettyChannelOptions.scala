package zio.aws.netty

import zio.aws.core.httpclient.ChannelOptions
import io.netty.channel.ChannelOption

/** Netty channel options that `NettyHttpClient.configured` sets on each
  * connection with the builder's `putChannelOption`. A `CONNECT_TIMEOUT_MILLIS`
  * option here takes precedence over `NettyClientConfig.connectionTimeout`.
  */
case class NettyChannelOptions(options: Vector[NettyOptionValue[_]]) {

  /** Returns these options followed by `sockOptions`, each converted to the
    * Netty `ChannelOption` with the same name (`ChannelOption.valueOf(name)`).
    */
  def withSocketOptions(sockOptions: ChannelOptions): NettyChannelOptions =
    NettyChannelOptions(
      options ++ sockOptions.options.map(opt =>
        NettyOptionValue(ChannelOption.valueOf(opt.key.name()), opt.value)
      )
    )
}
