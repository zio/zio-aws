package io.github.vigoo.zioaws.codegen

import software.amazon.awssdk.codegen.C2jModels

import scala.meta._

object TypeMapping {
  def toType(name: String, models: C2jModels): Type = {
    val shape = models.serviceModel().getShape(name)
    shape.getType match {
      case "map" =>
        t"""java.util.Map[${toType(shape.getMapKeyType.getShape, models)}, ${toType(shape.getMapValueType.getShape, models)}]"""
      case "list" =>
        t"""java.util.List[${toType(shape.getListMember.getShape, models)}]"""
      case "string" =>
        t"String"
      case _ =>
        Type.Name(name)
    }
  }

}
