package zio.aws.core.httpclient

/** Selects which HTTP versions an [[HttpClient]] built by
  * `HttpClient.fromScopedPerProtocol` supports.
  *
  * @see
  *   [[https://zio.dev/zio-aws/http/ HTTP Clients]]
  */
sealed trait Protocol

/** The [[Protocol]] values. */
object Protocol {

  /** HTTP/1.1 only: every service gets the HTTP/1.1 client. */
  case object Http11 extends Protocol

  /** HTTP/2 only: services without HTTP/2 support get no client. */
  case object Http2 extends Protocol

  /** Both: services that support HTTP/2 get the HTTP/2 client, and the others
    * get the HTTP/1.1 client.
    */
  case object Dual extends Protocol
}
