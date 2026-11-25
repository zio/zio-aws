package zio.aws.core

import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.utils.builder.SdkBuilder
import zio.prelude.data.Optional

/** Extension methods for AWS SDK builders that build a `T`. Import the members of an instance, such as `BuilderHelper[ClientOverrideConfiguration]`, to call `optionallyWith` on a builder; the generated service code and the HTTP client modules use it this way. */
trait BuilderHelper[T] {
  /** Adds `optionallyWith` for `zio.prelude.data.Optional` values to an AWS SDK builder of `T`. */
  implicit class BuilderOps[B <: SdkBuilder[B, T]](val builder: B) {
    /** Returns `withF(builder)(value)` when `opt` is `Optional.Present(value)`, and `builder` unchanged when `opt` is `Optional.Absent`. */
    def optionallyWith[P](opt: Optional[P])(withF: B => P => B): B =
      opt match {
        case Optional.Present(value) => withF(builder)(value)
        case Optional.Absent         => builder
      }
  }

  /** Adds `optionallyWith` for Scala `Option` values to an `SdkAsyncHttpClient` builder. */
  implicit class HttpClientBuilderOps[B <: SdkAsyncHttpClient.Builder[B]](
      val builder: B
  ) {
    /** Returns `withF(builder)(value)` when `opt` is `Some(value)`, and `builder` unchanged when `opt` is `None`. */
    def optionallyWith[P](opt: Option[P])(withF: B => P => B): B =
      opt match {
        case Some(value) => withF(builder)(value)
        case None        => builder
      }
  }
}

/** Creates [[BuilderHelper]] instances. */
object BuilderHelper {
  /** Returns a new [[BuilderHelper]] for builders that build a `T`. */
  def apply[T]: BuilderHelper[T] = new BuilderHelper[T] {}
}
