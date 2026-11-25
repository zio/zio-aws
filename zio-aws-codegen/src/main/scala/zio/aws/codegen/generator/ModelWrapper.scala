package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.ScalaType

import scala.meta.Defn

// TODO: remove fileName?
/**
 * The generated code for one model: the definitions in `code` and the type `generatedType` that they define. `fileName` is the optional name of the file for the code.
 */
case class ModelWrapper(
    fileName: Option[String],
    code: List[Defn],
    generatedType: ScalaType
) {
  /** Returns the simple name of `generatedType`. */
  def name: String = generatedType.name
}
