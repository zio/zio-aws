package zio.aws.codegen.generator

import scala.meta._

/**
 * The generated pieces for one field of a model: the constructor parameter, the getter calls and implementations, how the field is applied to the SDK builder, the editable getter and the wither, plus whether the field is required.
 */
case class ModelFieldFragments(
    paramDef: Term.Param,
    getterCall: Term,
    getterInterface: Decl.Def,
    getterImplementation: Defn.Val,
    zioGetterImplementation: Defn.Def,
    applyToBuilder: Term.Apply => Term.Apply,
    editableGetter: Defn.Def,
    wither: Defn.Def,
    isRequired: Boolean
) {
  /** Returns the name of the `wither` definition. */
  def witherName: Term.Name = wither.name
}
