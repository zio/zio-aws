package zio.aws.netty

/**
 * The proxy that the Netty client sends requests through. The client opens a `CONNECT` tunnel through the proxy, for `http` endpoints too.
 *
 * @param scheme the scheme used to connect to the proxy
 * @param nonProxyHosts hosts that the client connects to directly, without the proxy
 */
case class ProxyConfiguration(
    scheme: HttpOrHttps,
    host: String,
    port: Int,
    nonProxyHosts: Set[String]
)
