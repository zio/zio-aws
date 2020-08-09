package io.github.vigoo.zioaws.codegen.generator

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Member, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy

case class Model(name: String, shapeName: String, shape: Shape) {
  assert(shape != null)
}

object ModelCollector {
  def collectUsedModels(namingStrategy: NamingStrategy, model: C2jModels): Set[Model] = {
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
              existing + Model(requestName, requestShapeName.capitalize, requestShape)
            case None =>
              existing
          }
        case (None, Some(responseShapeName)) =>
          Option(model.serviceModel().getShape(responseShapeName)) match {
            case Some(responseShape) =>
              existing + Model(responseName, responseShapeName.capitalize, responseShape)
            case None =>
              existing
          }
        case (Some(requestShapeName), Some(responseShapeName)) =>
          val requestShapeOpt = Option(model.serviceModel().getShape(requestShapeName))
          val responseShapeOpt = Option(model.serviceModel().getShape(responseShapeName))

          existing union requestShapeOpt.map { requestShape =>
            Model(requestName, requestShapeName.capitalize, requestShape)
          }.toSet union responseShapeOpt.map { responseShape =>
            Model(responseName, responseShapeName.capitalize, responseShape)
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
                        collectShapes(model, result + Model(itemShapeName.capitalize, itemShapeName.capitalize, itemShape), Set.empty, itemShape)
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

    rootShapesWithPagination.map(_.shape).foldLeft(rootShapesWithPagination) { case (result, shape) =>
      collectShapes(model, result, Set.empty, shape)
    }
  }

  private def collectShapes(model: C2jModels, found: Set[Model], invalid: Set[String], shape: Shape): Set[Model] = {
    val subTypes =
      Option(shape.getMembers).map(_.asScala).getOrElse(Map.empty[String, Member]).values.map(_.getShape).toSet union
        Option(shape.getListMember).map(_.getShape).toSet union
        Option(shape.getMapKeyType).map(_.getShape).toSet union
        Option(shape.getMapValueType).map(_.getShape).toSet

    val newShapes = found.foldLeft(subTypes) { case (names, model) => names - model.shapeName } diff invalid
    val newModels = newShapes.flatMap(modelFromShape(model, _))
    val newInvalids = invalid union (newShapes diff newModels.map(_.shapeName))

    newModels.foldLeft(found) { case (result, m) => collectShapes(model, result + m, newInvalids, m.shape) }
  }

  private def modelFromShape(model: C2jModels, shapeName: String): Option[Model] = {
    Option(model.serviceModel().getShape(shapeName)).map { shape =>
      Model(shapeName.capitalize, shapeName.capitalize, shape)
    }
  }
}
