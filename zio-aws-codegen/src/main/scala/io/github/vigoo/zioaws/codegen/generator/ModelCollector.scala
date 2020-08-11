package io.github.vigoo.zioaws.codegen.generator

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.ShapeSubstitution
import software.amazon.awssdk.codegen.model.service.{Member, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.ZIO

trait ModelMap {
  def get(serviceModelName: String): ZIO[GeneratorContext, GeneratorFailure, Model]

  val all: Set[Model]
}

object ModelCollector {
  def collectUsedModels(namingStrategy: NamingStrategy, models: C2jModels): ModelMap = {
    val rootShapes = OperationCollector.getFilteredOperations(models).foldLeft(Set.empty[Model]) { case (existing, (opName, op)) =>
      val inputIsEventStream = OperationCollector.inputIsEventStreamOf(models, op)
      val outputIsEventStream = OperationCollector.outputIsEventStreamOf(models, op)

      val requestName = namingStrategy.getRequestClassName(opName)
      val responseName = namingStrategy.getResponseClassName(opName)

      val requestShapeName = if (inputIsEventStream) None else Option(op.getInput).map(_.getShape)
      val responseShapeName = if (outputIsEventStream) None else Option(op.getOutput).map(_.getShape)

      (requestShapeName, responseShapeName) match {
        case (None, None) => Set.empty
        case (Some(requestShapeName), None) =>
          Option(models.serviceModel().getShape(requestShapeName)) match {
            case Some(requestShape) =>
              existing + Model(requestName, requestShapeName.capitalize, finalType(models, requestShape), requestShape, requestName)
            case None =>
              existing
          }
        case (None, Some(responseShapeName)) =>
          Option(models.serviceModel().getShape(responseShapeName)) match {
            case Some(responseShape) =>
              existing + Model(responseName, responseShapeName.capitalize, finalType(models, responseShape), responseShape, responseName)
            case None =>
              existing
          }
        case (Some(requestShapeName), Some(responseShapeName)) =>
          val requestShapeOpt = Option(models.serviceModel().getShape(requestShapeName))
          val responseShapeOpt = Option(models.serviceModel().getShape(responseShapeName))

          existing union requestShapeOpt.map { requestShape =>
            Model(requestName, requestShapeName.capitalize, finalType(models, requestShape), requestShape, requestName)
          }.toSet union responseShapeOpt.map { responseShape =>
            Model(responseName, responseShapeName.capitalize, finalType(models, responseShape), responseShape, responseName)
          }.toSet
      }
    }

    val rootShapesWithPagination = models.paginatorsModel().getPaginators.asScala.toList.foldLeft(rootShapes) { case (result, (name, paginator)) =>
      if (paginator.isValid) {
        Option(paginator.getResultKey).flatMap(_.asScala.headOption) match {
          case Some(key) =>
            val outputShape = models.serviceModel().getShape(models.serviceModel().getOperation(name).getOutput.getShape)
            outputShape.getMembers.asScala.get(key) match {
              case Some(outputListMember) =>
                val listShape = models.serviceModel().getShape(outputListMember.getShape)
                Option(listShape.getListMember) match {
                  case Some(itemMember) =>
                    val itemShapeName = itemMember.getShape
                    Option(models.serviceModel().getShape(itemShapeName)) match {
                      case Some(itemShape) =>
                        collectShapes(namingStrategy, models, result + modelFromShape(namingStrategy, models, itemShape, itemShapeName), Set.empty, itemShape, itemShapeName)
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

    val all = rootShapesWithPagination.foldLeft(rootShapesWithPagination) { case (result, m) =>
      collectShapes(namingStrategy, models, result, Set.empty, m.shape, m.serviceModelName)
    }
    val map = all.map { m => (m.serviceModelName, m) }.toMap
    val substitutedMap = applySubstitutions(models, map)

    new ModelMap {
      override def get(serviceModelName: String): ZIO[GeneratorContext, GeneratorFailure, Model] =
        substitutedMap.get(serviceModelName) match {
          case Some(value) => ZIO.succeed(value)
          case None => ZIO.access[GeneratorContext](_.get.serviceName).flatMap { serviceName =>
            ZIO.fail(UnknownShapeReference(serviceName, serviceModelName))
          }
        }

      override val all: Set[Model] = substitutedMap.values.toSet
    }
  }

  private def collectShapes(namingStrategy: NamingStrategy, models: C2jModels, found: Set[Model], invalid: Set[String], shape: Shape, name: String): Set[Model] = {
    val subTypes =
      Option(shape.getMembers).map(_.asScala).getOrElse(Map.empty[String, Member]).values.map(_.getShape).toSet union
        Option(shape.getListMember).map(_.getShape).toSet union
        Option(shape.getMapKeyType).map(_.getShape).toSet union
        Option(shape.getMapValueType).map(_.getShape).toSet

    val newShapes = found.foldLeft(subTypes) { case (names, model) => names - model.serviceModelName } diff invalid
    val newModels = newShapes.flatMap(modelFromShapeName(namingStrategy, models, _))
    val newInvalids = invalid union (newShapes diff newModels.map(_.serviceModelName))

    newModels.foldLeft(found) { case (result, m) => collectShapes(namingStrategy, models, result + m, newInvalids, m.shape, m.serviceModelName) }
  }

  private def modelFromShapeName(namingStrategy: NamingStrategy, models: C2jModels, shapeName: String): Option[Model] = {
    Option(models.serviceModel().getShape(shapeName)).map { shape =>
      modelFromShape(namingStrategy, models, shape, shapeName)
    }
  }

  private def modelFromShape(namingStrategy: NamingStrategy, models: C2jModels, shape: Shape, shapeName: String): Model = {
    val finalName = Option(models.customizationConfig().getRenameShapes).map(_.asScala).getOrElse(Map.empty[String, String]).get(shapeName) match {
      case Some(renamedShapeName) => renamedShapeName
      case None => shapeName
    }

    val className = namingStrategy.getJavaClassName(finalName)
    Model(className, className, finalType(models, shape), shape, shapeName)
  }

  private def finalType(models: C2jModels, shape: Shape): ModelType = {
    ModelType.fromShape(shape)
  }

  private def applySubstitutions(models: C2jModels, map: Map[String, Model]): Map[String, Model] = {
    val substitutions = Option(models.customizationConfig().getShapeSubstitutions()).map(_.asScala).getOrElse(Map.empty[String, ShapeSubstitution])

    if (substitutions.isEmpty) {
      map
    } else {
      map.map { case (name, model) =>
        substitutions.get(name) match {
          case Some(substitution) =>
            Option(substitution.getEmitAsShape) match {
              case Some(newShape) =>
                val otherModel = map(newShape)
                name -> model.copy(
                  name = otherModel.name,
                  shapeName = otherModel.shapeName,
                  typ = otherModel.typ,
                  shape = otherModel.shape
                )
              case None =>
                name -> model
            }
          case None => name -> model
        }
      }
    }
  }
}
