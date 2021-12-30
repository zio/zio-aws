package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.Package

object Packages {
  val zio: Package = Package("zio")

  val zioaws: Package = zio / "aws"
  val zioawsCore: Package = zioaws / "core"
  val zioawsHttpClient: Package = zioawsCore / "httpclient"

  val awsCore: Package = Package("software", "amazon", "awssdk", "core")
  val awsAwsCore: Package = Package("software", "amazon", "awssdk", "awscore")

  val zioPrelude: Package = zio / "prelude"
}
