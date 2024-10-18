package zio.aws.codegen.generator

import software.amazon.awssdk.codegen.model.service.Shape
import zio.ZIO

import zio.aws.codegen.generator.context._
import zio.aws.codegen.generator.context.AwsGeneratorContext._
import _root_.io.github.vigoo.metagen.core._

object TypeMapping {
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
    "BigDecimal",
    "Instant"
  )

  def isBuiltIn(name: String): Boolean = {
    builtIns.contains(name)
  }

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
        toJavaType(model)
      case ModelType.Document =>
        toJavaType(model)
      case ModelType.Structure =>
        ZIO.succeed(model.generatedType / "ReadOnly")
      case _ =>
        ZIO.succeed(model.generatedType)
    }
  }
}
