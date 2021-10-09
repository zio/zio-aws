package io.github.vigoo.zioaws.core.httpclient

import java.net.SocketOption

case class OptionValue[T](key: SocketOption[T], value: T)
