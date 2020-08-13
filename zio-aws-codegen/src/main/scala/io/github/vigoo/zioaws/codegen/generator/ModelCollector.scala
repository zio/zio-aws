package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.generator.context.{GeneratorContext, serviceName}

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.ShapeSubstitution
import software.amazon.awssdk.codegen.model.service.{Member, Operation, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.ZIO

trait ModelMap {
  def get(serviceModelName: String): ZIO[GeneratorContext, GeneratorFailure, Model]

  val all: Set[Model]
}

object ModelCollector {
  def tryFindEventStreamShape(models: C2jModels, outputShape: String, alreadyChecked: Set[Shape] = Set.empty): Option[(String, Shape)] = {
    Option(models.serviceModel().getShape(outputShape)) match {
      case Some(shape) if !(alreadyChecked.contains(shape)) =>
        if (shape.isEventStream) {
          Some((outputShape, shape))
        } else {
          shape.getMembers
            .asScala
            .values
            .map(member => tryFindEventStreamShape(models, member.getShape, alreadyChecked + shape))
            .find(_.isDefined)
            .flatten
        }
      case _ =>
        None
    }
  }

  private def collectRequestResponseTypes(namingStrategy: NamingStrategy, models: C2jModels, ops: Map[String, Operation]): Set[Model] =
    ops
      .toList
      .flatMap { case (opName, op) =>
        val outputIsEventStream = OperationCollector.outputIsEventStreamOf(models, op)

        val requestName = namingStrategy.getRequestClassName(opName)
        val responseName = namingStrategy.getResponseClassName(opName)

        val request = Option(op.getInput).map(_.getShape)
        val response = if (outputIsEventStream) None else Option(op.getOutput).map(_.getShape)

        (request, response) match {
          case (None, None) => List.empty
          case (Some(requestShapeName), None) =>
            Option(models.serviceModel().getShape(requestShapeName)).map { requestShape =>
              Model(requestName, requestShapeName.capitalize, finalType(models, requestShape), requestShape, requestName)
            }.toList
          case (None, Some(responseShapeName)) =>
            Option(models.serviceModel().getShape(responseShapeName)).map { responseShape =>
              Model(responseName, responseShapeName.capitalize, finalType(models, responseShape), responseShape, responseName)
            }.toList
          case (Some(requestShapeName), Some(responseShapeName)) =>
            val requestShapeOpt = Option(models.serviceModel().getShape(requestShapeName))
            val responseShapeOpt = Option(models.serviceModel().getShape(responseShapeName))


            requestShapeOpt.map { requestShape =>
              Model(requestName, requestShapeName.capitalize, finalType(models, requestShape), requestShape, requestName)
            }.toList ::: responseShapeOpt.map { responseShape =>
              Model(responseName, responseShapeName.capitalize, finalType(models, responseShape), responseShape, responseName)
            }.toList
        }
      }.toSet

  private def collectInputEvents(namingStrategy: NamingStrategy, models: C2jModels, ops: Map[String, Operation]): Set[Model] =
    ops
      .toList
      .flatMap { case (opName, op) =>
        if (OperationCollector.inputIsEventStreamOf(models, op)) {
          tryFindEventStreamShape(models, op.getInput.getShape).flatMap { case (eventStreamName, _) =>
            modelFromShapeName(namingStrategy, models, eventStreamName)
          }
        } else {
          None
        }
      }.toSet

  private def collectOutputEvents(namingStrategy: NamingStrategy, models: C2jModels, ops: Map[String, Operation]): Set[Model] =
    ops
      .toList
      .flatMap { case (opName, op) =>
        if (OperationCollector.outputIsEventStreamOf(models, op)) {
          tryFindEventStreamShape(models, op.getOutput.getShape).flatMap { case (eventStreamShapeName, eventStreamShape) =>
            val name = eventStreamShape.getMembers.asScala.keys.head
            modelFromShapeName(namingStrategy, models, name)
          }
        } else {
          None
        }
      }.toSet

  private def collectPaginations(namingStrategy: NamingStrategy, models: C2jModels): Set[Model] =
    models.paginatorsModel().getPaginators
      .asScala
      .toList
      .flatMap { case (name, paginator) =>
        if (paginator.isValid) {
          Option(paginator.getResultKey).flatMap(_.asScala.headOption).flatMap { key =>
            val outputShape = models.serviceModel().getShape(models.serviceModel().getOperation(name).getOutput.getShape)
            outputShape.getMembers.asScala.get(key).flatMap { outputListMember =>
              val listShape = models.serviceModel().getShape(outputListMember.getShape)
              Option(listShape.getListMember).flatMap { itemMember =>
                val itemShapeName = itemMember.getShape
                Option(models.serviceModel().getShape(itemShapeName)).map { itemShape =>
                  modelFromShape(namingStrategy, models, itemShape, itemShapeName)
                }
              }
            }
          }
        } else {
          None
        }
      }.toSet

  def collectUsedModels(namingStrategy: NamingStrategy, models: C2jModels): ModelMap = {
    val ops = OperationCollector.getFilteredOperations(models)
    val rootShapes =
      collectRequestResponseTypes(namingStrategy, models, ops) union
        collectInputEvents(namingStrategy, models, ops) union
        collectOutputEvents(namingStrategy, models, ops) union
        collectPaginations(namingStrategy, models)

    val all = rootShapes.foldLeft(rootShapes) { case (result, m) =>
      collectShapes(namingStrategy, models, result, Set.empty, m.shape, m.serviceModelName)
    }
    val map = all.map { m => (m.serviceModelName, m) }.toMap
    val substitutedMap = applySubstitutions(models, map)

    new ModelMap {
      override def get(serviceModelName: String): ZIO[GeneratorContext, GeneratorFailure, Model] =
        substitutedMap.get(serviceModelName) match {
          case Some(value) => ZIO.succeed(value)
          case None => serviceName.flatMap { serviceName =>
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
