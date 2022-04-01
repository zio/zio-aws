package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.{Package, ScalaType}
import Packages._

import scala.meta._

object Types {
  val awsError: ScalaType = ScalaType(zioawsCore, "AwsError")
  def builderHelper(a: ScalaType): ScalaType =
    ScalaType(zioawsCore, "BuilderHelper", a)
  val builderHelper_ : ScalaType = builderHelper(ScalaType.any).unapplied

  val awsConfig: ScalaType = ScalaType(zioawsCore / "config", "AwsConfig")

  val serviceHttpCapabilities: ScalaType =
    ScalaType(Packages.zioawsHttpClient, "ServiceHttpCapabilities")

  def streamingOutputResult(
      r: ScalaType,
      e: ScalaType,
      a: ScalaType
  ): ScalaType = ScalaType(zioawsCore, "StreamingOutputResult", r, e, a)

  def chunk(a: ScalaType): ScalaType = ScalaType(Packages.zio, "Chunk", a)
  val chunk_ : ScalaType = chunk(ScalaType.any).unapplied

  def zio(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "ZIO", r, e, a)
  val zio_ : ScalaType =
    zio(ScalaType.any, ScalaType.any, ScalaType.any).unapplied
  def io(e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "IO", e, a)
  def ioAwsError(a: ScalaType): ScalaType = io(awsError, a)
  def zioAwsError(r: ScalaType, a: ScalaType): ScalaType = zio(r, awsError, a)
  def task(a: ScalaType): ScalaType = ScalaType(Packages.zio, "Task", a)

  def zioStream(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio / "stream", "ZStream", r, e, a)
  val zioStream_ : ScalaType =
    zioStream(ScalaType.any, ScalaType.any, ScalaType.any).unapplied
  def zioStreamAwsError(r: ScalaType, a: ScalaType): ScalaType =
    zioStream(r, awsError, a)
  def ioStreamAwsError(a: ScalaType): ScalaType =
    zioStream(ScalaType.any, awsError, a)

  def eventStreamResponseHandler(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(
      Packages.awsAwsCore / "eventstream",
      "EventStreamResponseHandler",
      a,
      b
    )
  def sdkPublisher(a: ScalaType): ScalaType =
    ScalaType(Packages.awsCore / "async", "SdkPublisher", a)
  def rsPublisher(a: ScalaType): ScalaType =
    ScalaType(Package("org", "reactivestreams"), "Publisher", a)
  val sdkBytes: ScalaType = ScalaType(Packages.awsCore, "SdkBytes")

  val throwable: ScalaType = ScalaType(Package.javaLang, "Throwable")
  val instant: ScalaType = ScalaType(Package.javaTime, "Instant")
  val bigDecimal: ScalaType = ScalaType(Package("scala", "math"), "BigDecimal")

  def newtype(t: ScalaType): ScalaType =
    ScalaType(Packages.zioPrelude, "Newtype", t)
  def subtype(t: ScalaType): ScalaType =
    ScalaType(Packages.zioPrelude, "Subtype", t)

  val awsDocument: ScalaType = ScalaType(Packages.awsCore / "document", "Document")

  def optional(t: ScalaType): ScalaType =
    ScalaType(Packages.zioPrelude / "data", "Optional", t)

  val optionalAbsent: ScalaType =
    ScalaType(Packages.zioPrelude / "data" / "Optional", "Absent")
  val optionalPresent: ScalaType =
    ScalaType(Packages.zioPrelude / "data" / "Optional", "Present")


  val optionalFromNullable: Term =
    q"zio.aws.core.internal.optionalFromNullable"
}
