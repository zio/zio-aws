package zio.aws.codegen.generator

import software.amazon.awssdk.codegen.model.service.Shape
import zio.ZIO

import zio.aws.codegen.generator.context._
import zio.aws.codegen.generator.context.AwsGeneratorContext._
import _root_.io.github.vigoo.metagen.core._

/** Maps AWS service model shapes to the Scala types that the generator emits
  * for them, as the Java SDK type, the wrapper type or the read-only wrapper
  * type.
  */
object TypeMapping {

  /** Returns `true` when the shape's type is `integer`, `long`, `float`,
    * `double`, `boolean`, `timestamp`, `blob`, `bigdecimal`, or `string`
    * without enum values, and `false` otherwise.
    */
  def isPrimitiveType(shape: Shape): Boolean =
    shape.getType match {
      case "string" if Option(shape.getEnumValues).isEmpty => true
      case "integer"                                       => true
      case "long"                                          => true
      case "float"                                         => true
      case "double"                                        => true
      case "boolean"                                       => true
      case "timestamp"                                     => true
      case "blob"                                          => true
      case "bigdecimal"                                    => true
      case _                                               => false
    }

  private val builtIns = Set(
    "String",
    "Boolean",
    "Int",
    "Long",
    "Float",
    "Double",
    "BigDecimal"
  )

  /** Returns `true` when `typ` is `java.time.Instant` and `false` for any other
    * type.
    */
  def resolvedToBuiltIn(typ: ScalaType): Boolean = {
    typ == Types.instant
  }

  /** Returns `true` when `name` is one of `String`, `Boolean`, `Int`, `Long`,
    * `Float`, `Double` or `BigDecimal`.
    */
  def isBuiltIn(name: String): Boolean = {
    builtIns.contains(name)
  }

  /** Returns the AWS SDK Java type for the model: `java.util.Map` and
    * `java.util.List` of the mapped key, value or item types, the boxed
    * `java.lang` types, `java.time.Instant`, `java.math.BigDecimal`, `SdkBytes`
    * for blobs, the SDK's `Document` for documents, and `model.sdkType` for
    * enums, structures and exceptions. Fails with `UnknownType` for a
    * `ModelType.Unknown` model.
    */
  def toJavaType(
      model: Model
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] = {
    val shape = model.shape
    model.typ match {
      case ModelType.Map =>
        for {
          keyModel <- get(shape.getMapKeyType.getShape)
          keyType <- toJavaType(keyModel)
          valueModel <- get(shape.getMapValueType.getShape)
          valueType <- toJavaType(valueModel)
        } yield ScalaType(Package.javaUtil, "Map", keyType, valueType)
      case ModelType.List =>
        for {
          itemModel <- get(shape.getListMember.getShape)
          itemType <- toJavaType(itemModel)
        } yield ScalaType(Package.javaUtil, "List", itemType)
      case ModelType.Enum =>
        ZIO.succeed(model.sdkType)
      case ModelType.String =>
        ZIO.succeed(ScalaType(Package.javaLang, "String"))
      case ModelType.Integer =>
        ZIO.succeed(ScalaType(Package.javaLang, "Integer"))
      case ModelType.Long =>
        ZIO.succeed(ScalaType(Package.javaLang, "Long"))
      case ModelType.Float =>
        ZIO.succeed(ScalaType(Package.javaLang, "Float"))
      case ModelType.Double =>
        ZIO.succeed(ScalaType(Package.javaLang, "Double"))
      case ModelType.Boolean =>
        ZIO.succeed(ScalaType(Package.javaLang, "Boolean"))
      case ModelType.Timestamp =>
        ZIO.succeed(ScalaType(Package.javaTime, "Instant"))
      case ModelType.BigDecimal =>
        ZIO.succeed(ScalaType(Package.javaMath, "BigDecimal"))
      case ModelType.Blob =>
        ZIO.succeed(Types.sdkBytes)
      case ModelType.Exception =>
        ZIO.succeed(model.sdkType)
      case ModelType.Structure =>
        ZIO.succeed(model.sdkType)
      case ModelType.Document =>
        ZIO.succeed(Types.awsDocument)
      case ModelType.Unknown(typ) =>
        getServiceName.flatMap(svc => ZIO.fail(UnknownType(svc, typ)))
    }
  }

  /** Returns the type of the model's value in the generated wrapper API: a
    * `Map` of the wrapped key and value types, an `Iterable` of the wrapped
    * item type, and `model.generatedType` for every other model type.
    */
  def toWrappedType(
      model: Model
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] = {
    model.typ match {
      case ModelType.Map =>
        for {
          keyModel <- get(model.shape.getMapKeyType.getShape)
          keyType <- toWrappedType(keyModel)
          valueModel <- get(model.shape.getMapValueType.getShape)
          valueType <- toWrappedType(valueModel)
        } yield ScalaType.map(keyType, valueType)
      case ModelType.List =>
        for {
          itemModel <- get(model.shape.getListMember.getShape)
          itemType <- toWrappedType(itemModel)
        } yield ScalaType(Package.scala, "Iterable", itemType)
      case _ =>
        ZIO.succeed(model.generatedType)
    }
  }

  /** Returns the type of the model's value in the generated read-only wrapper
    * API: a `Map` or `List` of the mapped types, `generatedType / "ReadOnly"`
    * for structures and exceptions, the Java type for documents, and
    * `model.generatedType` for every other model type.
    */
  def toWrappedTypeReadOnly(
      model: Model
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] = {
    model.typ match {
      case ModelType.Map =>
        for {
          keyModel <- get(model.shape.getMapKeyType.getShape)
          keyType <- toWrappedTypeReadOnly(keyModel)
          valueModel <- get(model.shape.getMapValueType.getShape)
          valueType <- toWrappedTypeReadOnly(valueModel)
        } yield ScalaType.map(keyType, valueType)
      case ModelType.List =>
        for {
          itemModel <- get(model.shape.getListMember.getShape)
          itemType <- toWrappedTypeReadOnly(itemModel)
        } yield ScalaType.list(itemType)
      case ModelType.Exception =>
        ZIO.succeed(model.generatedType / "ReadOnly")
      case ModelType.Document =>
        toJavaType(model)
      case ModelType.Structure =>
        ZIO.succeed(model.generatedType / "ReadOnly")
      case _ =>
        ZIO.succeed(model.generatedType)
    }
  }
}
