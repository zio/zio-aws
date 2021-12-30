package zio.aws.codegen.generator

import zio.aws.codegen.Parameters
import zio.aws.codegen.Parameters

trait HasConfig {
  protected val config: Parameters
}
