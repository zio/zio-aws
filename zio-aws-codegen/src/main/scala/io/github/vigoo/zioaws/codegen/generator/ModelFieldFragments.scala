package io.github.vigoo.zioaws.codegen.generator

import scala.meta._

case class ModelFieldFragments(paramDef: Term.Param,
                               getterCall: Term,
                               getterInterface: Decl.Def,
                               getterImplementation: Defn.Def,
                               zioGetterImplementation: Defn.Def,
                               applyToBuilder: Term.Apply => Term.Apply)