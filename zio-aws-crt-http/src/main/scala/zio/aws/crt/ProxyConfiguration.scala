package zio.aws.crt

final case class ProxyConfiguration(
    scheme: String,
    host: String,
    port: Int,
    username: String,
    password: String
)
