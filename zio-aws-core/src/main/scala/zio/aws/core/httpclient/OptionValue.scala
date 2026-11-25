package zio.aws.core.httpclient

import java.net.SocketOption

/** A socket option `key` together with the `value` to set it to. */
case class OptionValue[T](key: SocketOption[T], value: T)
