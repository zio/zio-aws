package zio.aws.crt

/**
 * The proxy for the CRT client. `AwsCrtHttpClient.configured` passes every field to the AWS SDK's CRT `ProxyConfiguration`.
 *
 * @param scheme the scheme used to connect to the proxy, `http` or `https`
 */
final case class ProxyConfiguration(
    scheme: String,
    host: String,
    port: Int,
    username: String,
    password: String
)
