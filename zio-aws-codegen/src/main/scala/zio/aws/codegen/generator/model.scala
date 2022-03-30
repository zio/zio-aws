package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.ScalaType
import software.amazon.awssdk.codegen.model.service.Shape

sealed trait ModelType
object ModelType {
  case object Map extends ModelType
  case object List extends ModelType
  case object Enum extends ModelType
  case object String extends ModelType
  case object Integer extends ModelType
  case object Long extends ModelType
  case object Float extends ModelType
  case object Double extends ModelType
  case object Boolean extends ModelType
  case object Timestamp extends ModelType
  case object BigDecimal extends ModelType
  case object Blob extends ModelType
  case object Structure extends ModelType
  case object Exception extends ModelType
  case object Document extends ModelType
  case class Unknown(name: String) extends ModelType

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

  def fromShape(shape: Shape): ModelType =
    shape.getType match {
      case "string" if Option(shape.getEnumValues).isDefined => Enum
      case "structure" if shape.isException                  => Exception
      case "structure" if shape.isDocument                   => Document
      case _ => fromString(shape.getType)
    }
}

case class Model(
    sdkType: ScalaType,
    generatedType: ScalaType,
    shapeName: String,
    typ: ModelType,
    shape: Shape,
    serviceModelName: String
)

case class NamedShape(name: String, shape: Shape)

case class PropertyNames(javaName: String, wrapperName: String)
