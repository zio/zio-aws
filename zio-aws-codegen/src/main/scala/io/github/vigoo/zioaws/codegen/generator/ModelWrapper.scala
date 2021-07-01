package io.github.vigoo.zioaws.codegen.generator

import scala.meta.Defn
import scala.meta.Term.Block

case class ModelWrapper(fileName: Option[String], code: List[Defn])
