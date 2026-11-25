package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.Package

/**
 * The `Package` values for the packages that the generated code refers to.
 */
object Packages {
  /** The `zio` package. */
  val zio: Package = Package("zio")

  /** The `zio.aws` package. */
  val zioaws: Package = zio / "aws"
  /** The `zio.aws.core` package. */
  val zioawsCore: Package = zioaws / "core"
  /** The `zio.aws.core.httpclient` package. */
  val zioawsHttpClient: Package = zioawsCore / "httpclient"

  /** The AWS SDK `software.amazon.awssdk.core` package. */
  val awsCore: Package = Package("software", "amazon", "awssdk", "core")
  /** The AWS SDK `software.amazon.awssdk.awscore` package. */
  val awsAwsCore: Package = Package("software", "amazon", "awssdk", "awscore")

  /** The `zio.prelude` package. */
  val zioPrelude: Package = zio / "prelude"
}
