package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.ScalaType
import software.amazon.awssdk.codegen.model.service.Shape

/** The kind of an AWS service model shape, as the generator classifies it.
  * `ModelType.fromString` and `ModelType.fromShape` produce the values.
  */
sealed trait ModelType

/** The cases of `ModelType` and the functions that map service model shape
  * types to them.
  */
object ModelType {

  /** A shape of type `map`. */
  case object Map extends ModelType

  /** A shape of type `list`. */
  case object List extends ModelType

  /** A `string` shape that has enum values. Only `ModelType.fromShape` produces
    * it.
    */
  case object Enum extends ModelType

  /** A `string` shape without enum values. */
  case object String extends ModelType

  /** A shape of type `integer`. */
  case object Integer extends ModelType

  /** A shape of type `long`. */
  case object Long extends ModelType

  /** A shape of type `float`. */
  case object Float extends ModelType

  /** A shape of type `double`. */
  case object Double extends ModelType

  /** A shape of type `boolean`. */
  case object Boolean extends ModelType

  /** A shape of type `timestamp`. */
  case object Timestamp extends ModelType

  /** A shape of type `bigdecimal`. */
  case object BigDecimal extends ModelType

  /** A shape of type `blob`. */
  case object Blob extends ModelType

  /** A shape of type `structure` that is neither an exception nor a document.
    */
  case object Structure extends ModelType

  /** A `structure` shape that the service model marks as an exception. Only
    * `ModelType.fromShape` produces it.
    */
  case object Exception extends ModelType

  /** A `structure` shape that the service model marks as a document. Only
    * `ModelType.fromShape` produces it.
    */
  case object Document extends ModelType

  /** A shape type that the generator does not recognise; `name` is the type
    * string from the service model.
    */
  case class Unknown(name: String) extends ModelType

  /** Maps the type string of a service model shape to a `ModelType`, returning
    * `Unknown(typ)` for a string that is not one of the types it matches. It
    * never returns `Enum`, `Exception` or `Document`; use `fromShape` for
    * those.
    */
  def fromString(typ: String): ModelType =
    typ match {
      case "map"        => Map
      case "list"       => List
      case "string"     => String
      case "integer"    => Integer
      case "long"       => Long
      case "float"      => Float
      case "double"     => Double
      case "boolean"    => Boolean
      case "timestamp"  => Timestamp
      case "bigdecimal" => BigDecimal
      case "blob"       => Blob
      case "structure"  => Structure
      case _            => Unknown(typ)
    }

  /** Classifies a service model `Shape`: a `string` with enum values is `Enum`,
    * a `structure` that is an exception is `Exception`, a `structure` that is a
    * document is `Document`, and any other shape goes through `fromString`.
    */
  def fromShape(shape: Shape): ModelType =
    shape.getType match {
      case "string" if Option(shape.getEnumValues).isDefined => Enum
      case "structure" if shape.isException                  => Exception
      case "structure" if shape.isDocument                   => Document
      case _ => fromString(shape.getType)
    }
}

/** A shape of an AWS service model together with the types the generator uses
  * for it: `sdkType` is the AWS SDK's Java type, `generatedType` is the type
  * the generator emits, and `serviceModelName` is the name of the service model
  * the shape belongs to.
  */
case class Model(
    sdkType: ScalaType,
    generatedType: ScalaType,
    shapeName: String,
    typ: ModelType,
    shape: Shape,
    serviceModelName: String
)

/** A service model `Shape` paired with its `name` in the model. */
case class NamedShape(name: String, shape: Shape)

/** The name of a property in the AWS SDK Java class (`javaName`) and the name
  * of the same property in the generated wrapper (`wrapperName`).
  */
case class PropertyNames(javaName: String, wrapperName: String)
