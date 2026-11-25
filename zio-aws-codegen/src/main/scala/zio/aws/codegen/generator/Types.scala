package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.{Package, ScalaType}
import Packages._

import scala.meta._

/** Builds the `ScalaType` references that the generator uses when it emits
  * code. Each member names a type in zio-aws-core, ZIO, ZIO Prelude, the AWS
  * SDK or the JDK, as a type or a term for the generated source.
  */
object Types {

  /** The `zio.aws.core.AwsError` type. */
  val awsError: ScalaType = ScalaType(zioawsCore, "AwsError")

  /** The `zio.aws.core.BuilderHelper` type applied to `a`. */
  def builderHelper(a: ScalaType): ScalaType =
    ScalaType(zioawsCore, "BuilderHelper", a)

  /** `zio.aws.core.BuilderHelper` applied to `ScalaType.any` and then
    * `unapplied`, so that it can be used as a term (for example to call its
    * members).
    */
  val builderHelper_ : ScalaType = builderHelper(ScalaType.any).unapplied

  /** The `zio.aws.core.config.AwsConfig` type. */
  val awsConfig: ScalaType = ScalaType(zioawsCore / "config", "AwsConfig")

  /** The `zio.aws.core.AwsServiceBase` type. */
  val awsServiceBase: ScalaType = ScalaType(zioawsCore, "AwsServiceBase")

  /** The `zio.aws.core.httpclient.ServiceHttpCapabilities` type. */
  val serviceHttpCapabilities: ScalaType =
    ScalaType(Packages.zioawsHttpClient, "ServiceHttpCapabilities")

  /** The `zio.aws.core.StreamingOutputResult` type applied to the environment
    * `r`, error `e` and value `a` types.
    */
  def streamingOutputResult(
      r: ScalaType,
      e: ScalaType,
      a: ScalaType
  ): ScalaType = ScalaType(zioawsCore, "StreamingOutputResult", r, e, a)

  /** The `zio.Chunk` type applied to `a`. */
  def chunk(a: ScalaType): ScalaType = ScalaType(Packages.zio, "Chunk", a)

  /** `zio.Chunk` applied to `ScalaType.any` and then `unapplied`, so that it
    * can be used as a term (for example to call `Chunk.fromArray`).
    */
  val chunk_ : ScalaType = chunk(ScalaType.any).unapplied

  /** The `zio.ZIO` type applied to the environment `r`, error `e` and value `a`
    * types.
    */
  def zio(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "ZIO", r, e, a)

  /** `zio.ZIO` applied to `ScalaType.any` three times and then `unapplied`, so
    * that it can be used as a term (for example to call `ZIO.serviceWithZIO`).
    */
  val zio_ : ScalaType =
    zio(ScalaType.any, ScalaType.any, ScalaType.any).unapplied

  /** The `zio.IO` type applied to the error `e` and value `a` types. */
  def io(e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "IO", e, a)

  /** The `zio.IO` type with `AwsError` as the error type and `a` as the value
    * type.
    */
  def ioAwsError(a: ScalaType): ScalaType = io(awsError, a)

  /** The `zio.ZIO` type with environment `r`, `AwsError` as the error type and
    * value type `a`.
    */
  def zioAwsError(r: ScalaType, a: ScalaType): ScalaType = zio(r, awsError, a)

  /** The `zio.Task` type applied to `a`. */
  def task(a: ScalaType): ScalaType = ScalaType(Packages.zio, "Task", a)

  /** The `zio.stream.ZStream` type applied to the environment `r`, error `e`
    * and element `a` types.
    */
  def zioStream(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio / "stream", "ZStream", r, e, a)

  /** `zio.stream.ZStream` applied to `ScalaType.any` three times and then
    * `unapplied`, so that it can be used as a term (for example to call
    * `ZStream.serviceWithStream`).
    */
  val zioStream_ : ScalaType =
    zioStream(ScalaType.any, ScalaType.any, ScalaType.any).unapplied

  /** The `zio.stream.ZStream` type with environment `r`, `AwsError` as the
    * error type and element type `a`.
    */
  def zioStreamAwsError(r: ScalaType, a: ScalaType): ScalaType =
    zioStream(r, awsError, a)

  /** The `zio.stream.ZStream` type with `ScalaType.any` as the environment,
    * `AwsError` as the error type and element type `a`.
    */
  def ioStreamAwsError(a: ScalaType): ScalaType =
    zioStream(ScalaType.any, awsError, a)

  /** The AWS SDK
    * `software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler`
    * type applied to `a` and `b`.
    */
  def eventStreamResponseHandler(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(
      Packages.awsAwsCore / "eventstream",
      "EventStreamResponseHandler",
      a,
      b
    )

  /** The AWS SDK `software.amazon.awssdk.core.async.SdkPublisher` type applied
    * to `a`.
    */
  def sdkPublisher(a: ScalaType): ScalaType =
    ScalaType(Packages.awsCore / "async", "SdkPublisher", a)

  /** The Reactive Streams `org.reactivestreams.Publisher` type applied to `a`.
    */
  def rsPublisher(a: ScalaType): ScalaType =
    ScalaType(Package("org", "reactivestreams"), "Publisher", a)

  /** The AWS SDK `software.amazon.awssdk.core.SdkBytes` type. */
  val sdkBytes: ScalaType = ScalaType(Packages.awsCore, "SdkBytes")

  /** The `java.lang.Throwable` type. */
  val throwable: ScalaType = ScalaType(Package.javaLang, "Throwable")

  /** The `java.time.Instant` type. */
  val instant: ScalaType = ScalaType(Package.javaTime, "Instant")

  /** The `scala.math.BigDecimal` type. */
  val bigDecimal: ScalaType = ScalaType(Package("scala", "math"), "BigDecimal")

  /** The `zio.prelude.Newtype` type applied to the underlying type `t`. */
  def newtype(t: ScalaType): ScalaType =
    ScalaType(Packages.zioPrelude, "Newtype", t)

  /** The `zio.prelude.Subtype` type applied to the underlying type `t`. */
  def subtype(t: ScalaType): ScalaType =
    ScalaType(Packages.zioPrelude, "Subtype", t)

  /** The `zio.prelude.Subtype` type without type arguments. */
  val subtype_ : ScalaType = ScalaType(Packages.zioPrelude, "Subtype")

  /** The AWS SDK `software.amazon.awssdk.core.document.Document` type. */
  val awsDocument: ScalaType =
    ScalaType(Packages.awsCore / "document", "Document")

  /** The `zio.prelude.data.Optional` type applied to `t`. */
  def optional(t: ScalaType): ScalaType =
    ScalaType(Packages.zioPrelude / "data", "Optional", t)

  /** The `zio.prelude.data.Optional.Absent` type. */
  val optionalAbsent: ScalaType =
    ScalaType(Packages.zioPrelude / "data" / "Optional", "Absent")

  /** The `zio.prelude.data.Optional.Present` type. */
  val optionalPresent: ScalaType =
    ScalaType(Packages.zioPrelude / "data" / "Optional", "Present")

  /** A term that refers to `zio.aws.core.internal.optionalFromNullable`, for
    * use in quasiquotes that build the generated code.
    */
  val optionalFromNullable: Term =
    q"zio.aws.core.internal.optionalFromNullable"
}
