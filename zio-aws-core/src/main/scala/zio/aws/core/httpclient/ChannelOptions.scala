package zio.aws.core.httpclient

/** A list of socket options for the connections of an HTTP client, such as the ones the zio-aws-netty module reads with the `channelOptions` descriptor in `zio.aws.core.httpclient.descriptors`. */
case class ChannelOptions(options: Vector[OptionValue[Any]])
