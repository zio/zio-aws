package zio.aws.codegen.generator

import scala.meta.Defn
import scala.meta.Term.Block

// TODO: remove fileName?
case class ModelWrapper(
    fileName: Option[String],
    code: List[Defn],
    name: String
)
