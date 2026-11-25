package zio.aws.netty

/**
 * The scheme that the Netty client uses to connect to a proxy: `HttpOrHttps.Http` or `HttpOrHttps.Https`.
 */
sealed trait HttpOrHttps {
  /** The scheme as the AWS SDK's proxy configuration takes it: `"http"` or `"https"`. */
  val asString: String
}

/** The two proxy schemes. */
object HttpOrHttps {

  /** Connects to the proxy over plain HTTP. */
  case object Http extends HttpOrHttps {
    override val asString: String = "http"
  }

  /** Connects to the proxy over HTTPS. */
  case object Https extends HttpOrHttps {
    override val asString: String = "https"
  }

}
