package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.clipp.zioapi.config.ClippConfig
import io.github.vigoo.zioaws.codegen.Parameters

trait HasConfig {
  protected val config: ClippConfig.Service[Parameters]
}
