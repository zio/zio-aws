package zio.aws.codegen.generator

import scala.meta._

case class ServiceMethod(
    interface: Decl,
    implementation: Defn,
    accessor: Defn
)

case class ServiceMethods(methods: List[ServiceMethod])

object ServiceMethods {
  def apply(methods: ServiceMethod*): ServiceMethods =
    ServiceMethods(methods.toList)
}
