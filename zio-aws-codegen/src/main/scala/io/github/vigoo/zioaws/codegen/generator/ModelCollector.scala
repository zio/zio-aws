package io.github.vigoo.zioaws.codegen.generator

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Member, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.ZIO

trait ModelMap {
  def get(serviceModelName: String): ZIO[GeneratorContext, GeneratorFailure, Model]
  val all: Set[Model]
}

case class Model(name: String, shapeName: String, shape: Shape, serviceModelName: String) {
  assert(shape != null)
}

object ModelCollector {
  def collectUsedModels(namingStrategy: NamingStrategy, model: C2jModels): ModelMap = {
    val rootShapes = OperationCollector.getFilteredOperations(model).foldLeft(Set.empty[Model]) { case (existing, (opName, op)) =>
      val requestName = namingStrategy.getRequestClassName(opName)
      val responseName = namingStrategy.getResponseClassName(opName)

      val requestShapeName = Option(op.getInput).map(_.getShape)
      val responseShapeName = Option(op.getOutput).map(_.getShape)

      (requestShapeName, responseShapeName) match {
        case (None, None) => Set.empty
        case (Some(requestShapeName), None) =>
          Option(model.serviceModel().getShape(requestShapeName)) match {
            case Some(requestShape) =>
              existing + Model(requestName, requestShapeName.capitalize, requestShape, requestName)
            case None =>
              existing
          }
        case (None, Some(responseShapeName)) =>
          Option(model.serviceModel().getShape(responseShapeName)) match {
            case Some(responseShape) =>
              existing + Model(responseName, responseShapeName.capitalize, responseShape, responseName)
            case None =>
              existing
          }
        case (Some(requestShapeName), Some(responseShapeName)) =>
          val requestShapeOpt = Option(model.serviceModel().getShape(requestShapeName))
          val responseShapeOpt = Option(model.serviceModel().getShape(responseShapeName))

          existing union requestShapeOpt.map { requestShape =>
            Model(requestName, requestShapeName.capitalize, requestShape, requestName)
          }.toSet union responseShapeOpt.map { responseShape =>
            Model(responseName, responseShapeName.capitalize, responseShape, responseName)
          }.toSet
      }
    }

    val rootShapesWithPagination = model.paginatorsModel().getPaginators.asScala.toList.foldLeft(rootShapes) { case (result, (name, paginator)) =>
      if (paginator.isValid) {
        Option(paginator.getResultKey).flatMap(_.asScala.headOption) match {
          case Some(key) =>
            val outputShape = model.serviceModel().getShape(model.serviceModel().getOperation(name).getOutput.getShape)
            outputShape.getMembers.asScala.get(key) match {
              case Some(outputListMember) =>
                val listShape = model.serviceModel().getShape(outputListMember.getShape)
                Option(listShape.getListMember) match {
                  case Some(itemMember) =>
                    val itemShapeName = itemMember.getShape
                    Option(model.serviceModel().getShape(itemShapeName)) match {
                      case Some(itemShape) =>
                        collectShapes(namingStrategy, model, result + modelFromShape(namingStrategy, itemShape, itemShapeName), Set.empty, itemShape)
                      case None =>
                          result
                    }
                  case None =>
                    result
                }
              case None =>
                result
            }
          case None =>
            result
        }
      } else {
        rootShapes
      }
    }

    val all = rootShapesWithPagination.map(_.shape).foldLeft(rootShapesWithPagination) { case (result, shape) =>
      collectShapes(namingStrategy, model, result, Set.empty, shape)
    }
    val map = all.map { m => (m.serviceModelName, m) }.toMap

    new ModelMap {
      override def get(serviceModelName: String): ZIO[GeneratorContext, GeneratorFailure, Model] =
        map.get(serviceModelName) match {
          case Some(value) => ZIO.succeed(value)
          case None => ZIO.access[GeneratorContext](_.get.serviceName).flatMap { serviceName =>
            ZIO.fail(UnknownShapeReference(serviceName, serviceModelName))
          }
        }
      override val all: Set[Model] = map.values.toSet
    }
  }

  private def collectShapes(namingStrategy: NamingStrategy, model: C2jModels, found: Set[Model], invalid: Set[String], shape: Shape): Set[Model] = {
    val subTypes =
      Option(shape.getMembers).map(_.asScala).getOrElse(Map.empty[String, Member]).values.map(_.getShape).toSet union
        Option(shape.getListMember).map(_.getShape).toSet union
        Option(shape.getMapKeyType).map(_.getShape).toSet union
        Option(shape.getMapValueType).map(_.getShape).toSet

    val newShapes = found.foldLeft(subTypes) { case (names, model) => names - model.serviceModelName } diff invalid
    val newModels = newShapes.flatMap(modelFromShapeName(namingStrategy, model, _))
    val newInvalids = invalid union (newShapes diff newModels.map(_.serviceModelName))

    newModels.foldLeft(found) { case (result, m) => collectShapes(namingStrategy, model, result + m, newInvalids, m.shape) }
  }

  private def modelFromShapeName(namingStrategy: NamingStrategy, model: C2jModels, shapeName: String): Option[Model] = {
    Option(model.serviceModel().getShape(shapeName)).map { shape =>
      modelFromShape(namingStrategy, shape, shapeName)
    }
  }

  private def modelFromShape(namingStrategy: NamingStrategy, shape: Shape, shapeName: String): Model = {
    val className = namingStrategy.getJavaClassName(shapeName)
    Model(className, className, shape, shapeName)
  }
}
