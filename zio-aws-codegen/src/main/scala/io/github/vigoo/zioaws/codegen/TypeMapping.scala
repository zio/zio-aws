package io.github.vigoo.zioaws.codegen

import software.amazon.awssdk.codegen.C2jModels

import scala.meta._
import _root_.io.github.vigoo.zioaws.codegen.generator.Model

object TypeMapping {
  def toType(model: Model, models: Map[String, Model], modelPkg: Term.Ref): Type = {
    val shape = model.shape
    shape.getType match {
      case "map" =>
        t"""java.util.Map[${toType(models(shape.getMapKeyType.getShape.capitalize), models, modelPkg)}, ${toType(models(shape.getMapValueType.getShape.capitalize), models, modelPkg)}]"""
      case "list" =>
        t"""java.util.List[${toType(models(shape.getListMember.getShape.capitalize), models, modelPkg)}]"""
      case "string" if Option(shape.getEnumValues).isDefined =>
        Type.Select(modelPkg, Type.Name(model.name))
      case "string" =>
        t"""String"""
      case "integer" =>
        t"""Int"""
      case "long" =>
        t"""Long"""
      case "float" =>
        t"""Float"""
      case "double" =>
        t"""Double"""
      case "boolean" =>
        t"""Boolean"""
      case "timestamp" =>
        t"""java.time.Instant"""
      case "blob" =>
        t"""SdkBytes"""
      case _ =>
        Type.Select(modelPkg, Type.Name(model.name))
    }
  }

  def toWrappedType(name: String, models: C2jModels): Type = {
    val shape = models.serviceModel().getShape(name)
    shape.getType match {
      case "map" =>
        t"""Map[${toWrappedType(shape.getMapKeyType.getShape, models)}, ${toWrappedType(shape.getMapValueType.getShape, models)}]"""
      case "list" =>
        t"""List[${toWrappedType(shape.getListMember.getShape, models)}]"""
      case _ =>
        Type.Name(name)
    }
  }

  def toWrappedTypeReadOnly(name: String, models: C2jModels): Type = {
    val shape = models.serviceModel().getShape(name)
    shape.getType match {
      case "map" =>
        t"""Map[${toWrappedTypeReadOnly(shape.getMapKeyType.getShape, models)}, ${toWrappedTypeReadOnly(shape.getMapValueType.getShape, models)}]"""
      case "list" =>
        t"""List[${toWrappedTypeReadOnly(shape.getListMember.getShape, models)}]"""
      case "structure" =>
        Type.Select(Term.Name(name), Type.Name("ReadOnly"))
      case _ =>
        Type.Name(name)
    }
  }
}
