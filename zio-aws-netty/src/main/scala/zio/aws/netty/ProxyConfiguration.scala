package zio.aws.netty

case class ProxyConfiguration(
    scheme: HttpOrHttps,
    host: String,
    port: Int,
    nonProxyHosts: Set[String]
)
