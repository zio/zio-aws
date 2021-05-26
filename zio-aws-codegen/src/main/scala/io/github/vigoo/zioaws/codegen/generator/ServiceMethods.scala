package io.github.vigoo.zioaws.codegen.generator

import scala.meta._

case class ServiceMethod(interface: Decl, implementation: Defn, accessor: Defn, mockObject: Defn, mockCompose: Defn)

case class ServiceMethods(methods: List[ServiceMethod])

object ServiceMethods {
  def apply(methods: ServiceMethod*): ServiceMethods =
    ServiceMethods(methods.toList)
}
