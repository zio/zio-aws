package zio.aws.core

import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.utils.builder.SdkBuilder
import zio.prelude.data.Optional

trait BuilderHelper[T] {
  implicit class BuilderOps[B <: SdkBuilder[B, T]](val builder: B) {
    def optionallyWith[P](opt: Optional[P])(withF: B => P => B): B =
      opt match {
        case Optional.Present(value) => withF(builder)(value)
        case Optional.Absent         => builder
      }
  }

  implicit class HttpClientBuilderOps[B <: SdkAsyncHttpClient.Builder[B]](
      val builder: B
  ) {
    def optionallyWith[P](opt: Option[P])(withF: B => P => B): B =
      opt match {
        case Some(value) => withF(builder)(value)
        case None        => builder
      }
  }
}

object BuilderHelper {
  def apply[T]: BuilderHelper[T] = new BuilderHelper[T] {}
}
