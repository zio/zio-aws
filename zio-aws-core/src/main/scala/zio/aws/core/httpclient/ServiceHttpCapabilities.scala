package zio.aws.core.httpclient

/** The HTTP versions that a generated service can use, which [[HttpClient]]
  * reads to choose a client. `supportsHttp2` is `true` when the service
  * supports HTTP/2.
  */
case class ServiceHttpCapabilities(supportsHttp2: Boolean)
