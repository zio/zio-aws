package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.metagen.core.Package

object Packages {
  val zioaws: Package = Package("io", "github", "vigoo", "zioaws")
  val zioawsCore: Package = zioaws / "core"
  val zioawsHttpClient: Package = zioawsCore / "httpclient"

  val zio: Package = Package("zio")

  val awsCore: Package = Package("software", "amazon", "awssdk", "core")

}
