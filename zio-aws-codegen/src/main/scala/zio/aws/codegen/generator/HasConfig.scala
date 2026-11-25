package zio.aws.codegen.generator

import zio.aws.codegen.Parameters
import zio.aws.codegen.Parameters

/**
 * Mixed into generator components that need the code generator's `Parameters` through a protected `config` value.
 */
trait HasConfig {
  protected val config: Parameters
}
