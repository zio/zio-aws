package zio.aws.codegen.generator

import scala.meta._

/** The three source fragments that the generator emits for one operation: the
  * declaration in the service trait, its implementation and its accessor in the
  * companion.
  */
case class ServiceMethod(
    interface: Decl,
    implementation: Defn,
    accessor: Defn
)

/** The methods that the generator emits for one operation. */
case class ServiceMethods(methods: List[ServiceMethod])

/** Constructors of [[ServiceMethods]]. */
object ServiceMethods {

  /** Creates a [[ServiceMethods]] from the given methods. */
  def apply(methods: ServiceMethod*): ServiceMethods =
    ServiceMethods(methods.toList)
}
