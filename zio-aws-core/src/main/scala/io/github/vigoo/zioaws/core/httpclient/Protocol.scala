package io.github.vigoo.zioaws.core.httpclient

sealed trait Protocol
object Protocol {
  case object Http11 extends Protocol
  case object Http2 extends Protocol
  case object Dual extends Protocol
}
