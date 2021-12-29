package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.ScalaType

import scala.meta.Defn
import scala.meta.Term.Block

// TODO: remove fileName?
case class ModelWrapper(
    fileName: Option[String],
    code: List[Defn],    
    generatedType: ScalaType
) {
    def name: String = generatedType.name
}
