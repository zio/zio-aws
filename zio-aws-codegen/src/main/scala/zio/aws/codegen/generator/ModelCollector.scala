package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.{Package, ScalaType}
import zio.aws.codegen.generator.TypeMapping.{
  isBuiltIn,
  isPrimitiveType
}
import zio.aws.codegen.generator.context.{
  AwsGeneratorContext,
  getServiceName
}
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.ShapeSubstitution
import software.amazon.awssdk.codegen.model.service.{Member, Operation, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.ZIO

import scala.jdk.CollectionConverters._

trait ModelMap {
  def get(
      serviceModelName: String
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Model]

  val all: Set[Model]
}

object ModelCollector {
  def tryFindEventStreamShape(
      models: C2jModels,
      outputShape: String,
      alreadyChecked: Set[Shape] = Set.empty
  ): Option[NamedShape] = {
    Option(models.serviceModel().getShape(outputShape)) match {
      case Some(shape) if !alreadyChecked.contains(shape) =>
        if (shape.isEventstream) {
          Some(NamedShape(outputShape, shape))
        } else {
          shape.getMembers.asScala.values
            .map(member =>
              tryFindEventStreamShape(
                models,
                member.getShape,
                alreadyChecked + shape
              )
            )
            .find(_.isDefined)
            .flatten
        }
      case _ =>
        None
    }
  }

  private def collectRequestResponseTypes(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels,
      ops: Map[String, Operation]
  ): Set[Model] =
    ops.toList.flatMap { case (opName, op) =>
      val outputIsEventStream =
        OperationCollector.outputIsEventStreamOf(models, op)

      val requestName = namingStrategy.getRequestClassName(opName)
      val responseName = namingStrategy.getResponseClassName(opName)

      val request = Option(op.getInput).map(_.getShape)
      val response =
        if (outputIsEventStream) None
        else Option(op.getOutput).map(_.getShape)

      (request, response) match {
        case (None, None) => List.empty
        case (Some(requestShapeName), None) =>
          Option(models.serviceModel().getShape(requestShapeName)).map {
            requestShape =>
              Model(
                ScalaType(modelPkg, requestName),
                ScalaType(targetModelPkg, requestName),
                requestShapeName.capitalize,
                finalType(requestShape),
                requestShape,
                requestName
              )
          }.toList
        case (None, Some(responseShapeName)) =>
          Option(models.serviceModel().getShape(responseShapeName)).map {
            responseShape =>
              Model(
                ScalaType(modelPkg, responseName),
                ScalaType(targetModelPkg, responseName),
                responseShapeName.capitalize,
                finalType(responseShape),
                responseShape,
                responseName
              )
          }.toList
        case (Some(requestShapeName), Some(responseShapeName)) =>
          val requestShapeOpt =
            Option(models.serviceModel().getShape(requestShapeName))
          val responseShapeOpt =
            Option(models.serviceModel().getShape(responseShapeName))

          requestShapeOpt.map { requestShape =>
            Model(
              ScalaType(modelPkg, requestName),
              ScalaType(targetModelPkg, requestName),
              requestShapeName.capitalize,
              finalType(requestShape),
              requestShape,
              requestName
            )
          }.toList ::: responseShapeOpt.map { responseShape =>
            Model(
              ScalaType(modelPkg, responseName),
              ScalaType(targetModelPkg, responseName),
              responseShapeName.capitalize,
              finalType(responseShape),
              responseShape,
              responseName
            )
          }.toList
      }
    }.toSet

  private def collectInputEvents(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels,
      ops: Map[String, Operation]
  ): Set[Model] =
    ops.toList.flatMap { case (_, op) =>
      if (OperationCollector.inputIsEventStreamOf(models, op)) {
        tryFindEventStreamShape(models, op.getInput.getShape).flatMap {
          eventStream =>
            modelFromShapeName(
              modelPkg,
              targetModelPkg,
              namingStrategy,
              models,
              eventStream.name
            )
        }
      } else {
        None
      }
    }.toSet

  private def collectOutputEvents(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels,
      ops: Map[String, Operation]
  ): Set[Model] =
    ops.toList.flatMap { case (_, op) =>
      if (OperationCollector.outputIsEventStreamOf(models, op)) {
        tryFindEventStreamShape(models, op.getOutput.getShape).flatMap {
          eventStream =>
            val name = eventStream.shape.getMembers.asScala.keys.head
            modelFromShapeName(
              modelPkg,
              targetModelPkg,
              namingStrategy,
              models,
              name
            )
        }
      } else {
        None
      }
    }.toSet

  private def collectPaginations(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels
  ): Set[Model] =
    models
      .paginatorsModel()
      .getPagination
      .asScala
      .toList
      .flatMap { case (name, paginator) =>
        if (paginator.isValid) {
          Option(paginator.getResultKey)
            .flatMap(_.asScala.headOption)
            .flatMap { key =>
              val outputShape = models
                .serviceModel()
                .getShape(
                  models.serviceModel().getOperation(name).getOutput.getShape
                )
              outputShape.getMembers.asScala.get(key).flatMap {
                outputListMember =>
                  val listShape =
                    models.serviceModel().getShape(outputListMember.getShape)
                  Option(listShape.getListMember).flatMap { itemMember =>
                    val itemShapeName = itemMember.getShape
                    Option(models.serviceModel().getShape(itemShapeName))
                      .map { itemShape =>
                        modelFromShape(
                          modelPkg,
                          targetModelPkg,
                          namingStrategy,
                          models,
                          itemShape,
                          itemShapeName
                        )
                      }
                  }
              }
            }
        } else {
          None
        }
      }
      .toSet

  def collectUsedModels(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels
  ): ModelMap = {
    val ops = OperationCollector.getFilteredOperations(models)
    val rootShapes =
      collectRequestResponseTypes(
        modelPkg,
        targetModelPkg,
        namingStrategy,
        models,
        ops
      ) union
        collectInputEvents(
          modelPkg,
          targetModelPkg,
          namingStrategy,
          models,
          ops
        ) union
        collectOutputEvents(
          modelPkg,
          targetModelPkg,
          namingStrategy,
          models,
          ops
        ) union
        collectPaginations(modelPkg, targetModelPkg, namingStrategy, models)

    val all = rootShapes.foldLeft(rootShapes) { case (result, m) =>
      collectShapes(
        modelPkg,
        targetModelPkg: Package,
        namingStrategy,
        models,
        result,
        Set.empty,
        m.shape
      )
    }
    val map = all.map { m => (m.serviceModelName, m) }.toMap
    val substitutedMap = applySubstitutions(models, map)

    new ModelMap {
      override def get(
          serviceModelName: String
      ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Model] =
        substitutedMap.get(serviceModelName) match {
          case Some(value) => ZIO.succeed(value)
          case None =>
            getServiceName.flatMap { serviceName =>
              ZIO.fail(UnknownShapeReference(serviceName, serviceModelName))
            }
        }

      override val all: Set[Model] = substitutedMap.values.toSet
    }
  }

  private def collectShapes(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels,
      found: Set[Model],
      invalid: Set[String],
      shape: Shape
  ): Set[Model] = {
    val subTypes =
      Option(shape.getMembers)
        .map(_.asScala)
        .getOrElse(Map.empty[String, Member])
        .values
        .map(_.getShape)
        .toSet union
        Option(shape.getListMember).map(_.getShape).toSet union
        Option(shape.getMapKeyType).map(_.getShape).toSet union
        Option(shape.getMapValueType).map(_.getShape).toSet

    val newShapes = found.foldLeft(subTypes) { case (names, model) =>
      names - model.serviceModelName
    } diff invalid
    val newModels =
      newShapes.flatMap(
        modelFromShapeName(modelPkg, targetModelPkg, namingStrategy, models, _)
      )
    val newInvalids =
      invalid union (newShapes diff newModels.map(_.serviceModelName))

    newModels.foldLeft(found) { case (result, m) =>
      collectShapes(
        modelPkg,
        targetModelPkg,
        namingStrategy,
        models,
        result + m,
        newInvalids,
        m.shape
      )
    }
  }

  private def modelFromShapeName(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels,
      shapeName: String
  ): Option[Model] = {
    Option(models.serviceModel().getShape(shapeName)).map { shape =>
      modelFromShape(
        modelPkg,
        targetModelPkg,
        namingStrategy,
        models,
        shape,
        shapeName
      )
    }
  }

  private def modelFromShape(
      modelPkg: Package,
      targetModelPkg: Package,
      namingStrategy: NamingStrategy,
      models: C2jModels,
      shape: Shape,
      shapeName: String
  ): Model = {
    val finalName = Option(models.customizationConfig().getRenameShapes)
      .map(_.asScala)
      .getOrElse(Map.empty[String, String])
      .get(shapeName) match {
      case Some(renamedShapeName) => renamedShapeName
      case None                   => shapeName
    }

    val className = namingStrategy.getShapeClassName(finalName)
    val isPrimitive = isPrimitiveType(shape) && !isBuiltIn(shapeName)

    val typ = finalType(shape)
    val sourcePkg = modelPkg
    val targetPkg = targetModelPkg

    val (sdkType, generatedType) =
      typ match {
        case ModelType.String if isBuiltIn(className) =>
          (ScalaType.string, ScalaType.string)
        case ModelType.Integer if isBuiltIn(className) =>
          (ScalaType.int, ScalaType.int)
        case ModelType.Long if isBuiltIn(className) =>
          (ScalaType.long, ScalaType.long)
        case ModelType.Float if isBuiltIn(className) =>
          (ScalaType.float, ScalaType.float)
        case ModelType.Double if isBuiltIn(className) =>
          (ScalaType.double, ScalaType.double)
        case ModelType.Boolean if isBuiltIn(className) =>
          (ScalaType.boolean, ScalaType.boolean)
        case ModelType.Timestamp if isBuiltIn(className) =>
          (Types.instant, Types.instant)
        case ModelType.BigDecimal if isBuiltIn(className) =>
          (Types.bigDecimal, Types.bigDecimal)
        case ModelType.Blob if isBuiltIn(className) =>
          (Types.chunk(ScalaType.byte), Types.chunk(ScalaType.byte))
        case _ =>
          (ScalaType(sourcePkg, className), ScalaType(targetPkg, className))
      }

//    println(s"Found model ${className} ${sdkType} <-> ${generatedType} with shape name ${shapeName} and type $typ (built-in: ${isBuiltIn(shapeName)}, primitive: $isPrimitive)")

    Model(
      sdkType,
      generatedType,
      shapeName = className,
      typ,
      shape = shape,
      serviceModelName = shapeName
    )
  }

  private def finalType(shape: Shape): ModelType = {
    ModelType.fromShape(shape)
  }

  private def applySubstitutions(
      models: C2jModels,
      map: Map[String, Model]
  ): Map[String, Model] = {
    val substitutions = Option(
      models.customizationConfig().getShapeSubstitutions
    ).map(_.asScala).getOrElse(Map.empty[String, ShapeSubstitution])

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
                  sdkType = otherModel.sdkType,
                  generatedType = otherModel.generatedType,
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
