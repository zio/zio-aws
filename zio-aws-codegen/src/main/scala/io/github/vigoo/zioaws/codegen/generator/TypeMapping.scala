package io.github.vigoo.zioaws.codegen.generator

import software.amazon.awssdk.codegen.model.service.Shape
import zio.ZIO

import scala.meta._

object TypeMapping {
  def isPrimitiveType(shape: Shape): Boolean = shape.getType match {
    case "string" if Option(shape.getEnumValues).isEmpty => true
    case "integer" => true
    case "long" => true
    case "float" => true
    case "double" => true
    case "boolean" => true
    case "timestamp" => true
    case "blob" => true
    case _ => false
  }

  private val builtIns = Set("String", "Boolean", "Int", "Integer", "Long", "Float", "Double")
  def isBuiltIn(name: String): Boolean = {
    builtIns.contains(name)
  }

  def toType(model: Model, models: ModelMap, modelPkg: Term.Ref): ZIO[GeneratorContext, GeneratorFailure, Type] = {
    val shape = model.shape
    shape.getType match {
      case "map" =>
        for {
          keyModel <- models.get(shape.getMapKeyType.getShape)
          keyType <- toType(keyModel, models, modelPkg)
          valueModel <- models.get(shape.getMapValueType.getShape)
          valueType <- toType(valueModel, models, modelPkg)
        } yield t"""java.util.Map[$keyType, $valueType]"""
      case "list" =>
        for {
          itemModel <- models.get(shape.getListMember.getShape)
          itemType <- toType(itemModel, models, modelPkg)
        } yield t"""java.util.List[$itemType]"""
      case "string" if Option(shape.getEnumValues).isDefined =>
        ZIO.succeed(Type.Select(modelPkg, Type.Name(model.name)))
      case "string" =>
        ZIO.succeed(t"""String""")
      case "integer" =>
        ZIO.succeed(t"""Int""")
      case "long" =>
        ZIO.succeed(t"""Long""")
      case "float" =>
        ZIO.succeed(t"""Float""")
      case "double" =>
        ZIO.succeed(t"""Double""")
      case "boolean" =>
        ZIO.succeed(t"""Boolean""")
      case "timestamp" =>
        ZIO.succeed(t"""java.time.Instant""")
      case "blob" =>
        ZIO.succeed(t"""SdkBytes""")
      case _ =>
        ZIO.succeed(Type.Select(modelPkg, Type.Name(model.name)))
    }
  }

  def toWrappedType(model: Model, models: ModelMap): ZIO[GeneratorContext, GeneratorFailure, Type] = {
    model.shape.getType match {
      case "map" =>
        for {
          keyModel <- models.get(model.shape.getMapKeyType.getShape)
          keyType <- toWrappedType(keyModel, models)
          valueModel <- models.get(model.shape.getMapValueType.getShape)
          valueType <- toWrappedType(valueModel, models)
        } yield t"""Map[$keyType, $valueType]"""
      case "list" =>
        for {
          itemModel <- models.get(model.shape.getListMember.getShape)
          itemType <- toWrappedType(itemModel, models)
        } yield t"""List[$itemType]"""
      case _ if isPrimitiveType(model.shape) && !isBuiltIn(model.shapeName) =>
        ZIO.succeed(Type.Select(Term.Name("primitives"), Type.Name(model.name)))
      case _ =>
        ZIO.succeed(Type.Name(model.name))
    }
  }

  def toWrappedTypeReadOnly(model: Model, models: ModelMap): ZIO[GeneratorContext, GeneratorFailure, Type] = {
    model.shape.getType match {
      case "map" =>
        for {
          keyModel <- models.get(model.shape.getMapKeyType.getShape)
          keyType <- toWrappedTypeReadOnly(keyModel, models)
          valueModel <- models.get(model.shape.getMapValueType.getShape)
          valueType <- toWrappedTypeReadOnly(valueModel, models)
        } yield t"""Map[$keyType, $valueType]"""
      case "list" =>
        for {
          itemModel <- models.get(model.shape.getListMember.getShape)
          itemType <- toWrappedTypeReadOnly(itemModel, models)
        } yield t"""List[$itemType]"""
      case "structure" =>
        ZIO.succeed(Type.Select(Term.Name(model.name), Type.Name("ReadOnly")))
      case _ if isPrimitiveType(model.shape) && !isBuiltIn(model.shapeName) =>
        ZIO.succeed(Type.Select(Term.Name("primitives"), Type.Name(model.name)))
      case _ =>
        ZIO.succeed(Type.Name(model.name))
    }
  }
}
