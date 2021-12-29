package zio.aws.netty

sealed trait HttpOrHttps {
  val asString: String
}

object HttpOrHttps {

  case object Http extends HttpOrHttps {
    override val asString: String = "http"
  }

  case object Https extends HttpOrHttps {
    override val asString: String = "https"
  }

}
