package zio.aws.codegen.generator

import scala.meta._

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
  def witherName: Term.Name = wither.name
}
