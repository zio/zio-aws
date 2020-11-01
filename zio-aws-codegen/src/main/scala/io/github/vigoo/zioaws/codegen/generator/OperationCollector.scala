package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.generator.TypeMapping.{
  toJavaType,
  toWrappedTypeReadOnly
}
import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.loader
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import zio.ZIO

import scala.jdk.CollectionConverters._

object OperationCollector {
  val overrides: Set[PaginationOverride] = Set(
    PaginationNotSupported(
      loader.ModelId("greengrass", None),
      "GetDeviceDefinitionVersion"
    ),
    PaginationNotSupported(
      loader.ModelId("greengrass", None),
      "GetSubscriptionDefinitionVersion"
    ),
    PaginationNotSupported(
      loader.ModelId("greengrass", None),
      "GetFunctionDefinitionVersion"
    ),
    PaginationNotSupported(
      loader.ModelId("greengrass", None),
      "GetConnectorDefinitionVersion"
    ),
    PaginationNotSupported(
      loader.ModelId("budgets", None),
      "DescribeBudgetPerformanceHistory"
    ),
    PaginationNotSupported(loader.ModelId("athena", None), "GetQueryResults"),
    PaginationNotSupported(
      loader.ModelId("guardduty", None),
      "GetUsageStatistics"
    ),
    SelectPaginatedStringMember(
      loader.ModelId("fms", None),
      "GetProtectionStatus",
      "Data"
    ),
    SelectPaginatedListMember(
      loader.ModelId("cloudformation", None),
      "DescribeChangeSet",
      "Changes"
    ),
    SelectPaginatedListMember(
      loader.ModelId("ec2", None),
      "DescribeVpcEndpointServices",
      "ServiceDetails"
    ),
    SelectPaginatedListMember(
      loader.ModelId("pi", None),
      "DescribeDimensionKeys",
      "Keys"
    ),
    SelectPaginatedListMember(
      loader.ModelId("cognitosync", None),
      "ListRecords",
      "Records"
    ),
    SelectPaginatedListMember(
      loader.ModelId("textract", None),
      "GetDocumentAnalysis",
      "Blocks"
    ),
    SelectPaginatedListMember(
      loader.ModelId("textract", None),
      "GetDocumentTextDetection",
      "Blocks"
    ),
    SelectPaginatedListMember(
      loader.ModelId("resourcegroups", None),
      "ListGroups",
      "Groups"
    ),
    SelectPaginatedListMember(
      loader.ModelId("resourcegroups", None),
      "SearchResources",
      "ResourceIdentifiers"
    ),
    SelectPaginatedListMember(
      loader.ModelId("resourcegroups", None),
      "ListGroupResources",
      "ResourceIdentifiers"
    )
  )

  case class OverrideKey(id: loader.ModelId, opName: String)

  val overrideMap: Map[OverrideKey, PaginationOverride] =
    overrides.map(o => o.toKey -> o).toMap

  def getFilteredOperations(models: C2jModels): Map[String, Operation] =
    models
      .serviceModel()
      .getOperations
      .asScala
      .toMap
      .filter { case (_, op) => !op.isDeprecated }
      .filter { case (opName, _) =>
        !isExcluded(models.customizationConfig(), opName)
      }

  def inputIsStreamingOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getInput)
      .flatMap(input => Option(models.serviceModel().getShape(input.getShape)))
      .exists(hasStreamingMember(models, _))

  def outputIsStreamingOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getOutput)
      .flatMap(output =>
        Option(models.serviceModel().getShape(output.getShape))
      )
      .exists(hasStreamingMember(models, _))

  def inputIsEventStreamOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getInput)
      .flatMap(input => Option(models.serviceModel().getShape(input.getShape)))
      .exists(hasEventStreamMember(models, _))

  def outputIsEventStreamOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getOutput)
      .flatMap(output =>
        Option(models.serviceModel().getShape(output.getShape))
      )
      .exists(hasEventStreamMember(models, _))

  def get(
      opName: String,
      op: Operation
  ): ZIO[GeneratorContext, GeneratorFailure, OperationMethodType] = {
    getService.flatMap { id =>
      getModels.flatMap { models =>
        val inputIsStreaming = inputIsStreamingOf(models, op)
        val outputIsStreaming = outputIsStreamingOf(models, op)

        val inputIsEventStream = inputIsEventStreamOf(models, op)
        val outputIsEventStream = outputIsEventStreamOf(models, op)

        if (inputIsStreaming && outputIsStreaming) {
          ZIO.succeed(StreamedInputOutput)
        } else if (inputIsStreaming) {
          ZIO.succeed(StreamedInput)
        } else if (outputIsStreaming) {
          ZIO.succeed(StreamedOutput)
        } else if (inputIsEventStream && outputIsEventStream) {
          ZIO.succeed(EventStreamInputOutput)
        } else if (inputIsEventStream) {
          ZIO.succeed(EventStreamInput)
        } else if (outputIsEventStream) {
          ZIO.succeed(EventStreamOutput)
        } else {
          if (op.getOutput == null && op.getInput == null) {
            ZIO.succeed(UnitToUnit)
          } else if (op.getOutput == null) {
            ZIO.succeed(RequestToUnit)
          } else if (op.getInput == null) {
            ZIO.succeed(UnitToResponse)
          } else {

            val outputShape =
              models.serviceModel().getShape(op.getOutput.getShape)
            val inputShape =
              models.serviceModel().getShape(op.getInput.getShape)

            if (
              outputShape.getMembers.containsKey("NextToken") &&
              inputShape.getMembers.containsKey("NextToken")
            ) {

              getPaginationDefinition(opName, op).map { paginationDefinition =>
                RequestResponse(paginationDefinition)
              }
            } else {
              getJavaSdkPaginatorDefinition(opName, op, models) match {
                case Some(createDef) =>
                  // Special paginator with Java SDK support
                  for {
                    paginatorDef <- createDef
                  } yield RequestResponse(pagination = Some(paginatorDef))
                case None =>
                  ZIO.succeed(RequestResponse(pagination = None))
              }
            }
          }
        }
      }
    }
  }

  private def getPaginationDefinition(
      opName: String,
      op: Operation
  ): ZIO[GeneratorContext, GeneratorFailure, Option[PaginationDefinition]] = {
    getService.flatMap { id =>
      getModels.flatMap { models =>
        val outputShape = models.serviceModel().getShape(op.getOutput.getShape)

        overrideMap.get(OverrideKey(id, opName)) match {
          case Some(PaginationNotSupported(_, _)) =>
            ZIO.none
          case Some(SelectPaginatedListMember(_, _, memberName)) =>
            val listShapeName = outputShape.getMembers.get(memberName).getShape
            for {
              listModel <- context.get(listShapeName)
              listShape = listModel.shape
              itemShapeName = listShape.getListMember.getShape
              itemModel <- context.get(itemShapeName)
            } yield Some(
              ListPaginationDefinition(
                memberName,
                listModel,
                itemModel,
                isSimple = false
              )
            )
          case Some(SelectPaginatedStringMember(_, _, memberName)) =>
            val stringShapeName =
              outputShape.getMembers.get(memberName).getShape
            for {
              stringModel <- context.get(stringShapeName)
            } yield Some(
              StringPaginationDefinition(
                memberName,
                stringModel,
                isSimple = false
              )
            )
          case None =>
            val otherOutputMembers =
              outputShape.getMembers.asScala.toMap - "NextToken"
            val outputMembersWithListType = otherOutputMembers.filter {
              case (name, member) =>
                models
                  .serviceModel()
                  .getShape(member.getShape)
                  .getType == "list"
            }
            val outputMembersWithMapType = otherOutputMembers.filter {
              case (name, member) =>
                models.serviceModel().getShape(member.getShape).getType == "map"
            }
            val outputMembersWithStringType = otherOutputMembers.filter {
              case (name, member) =>
                val shape = models.serviceModel().getShape(member.getShape)
                shape.getType == "string" && Option(shape.getEnumValues)
                  .map(_.asScala)
                  .getOrElse(List.empty)
                  .isEmpty
            }

            val isSimple = otherOutputMembers.size == 1

            if (outputMembersWithListType.size == 1) {
              val memberName = outputMembersWithListType.keys.head
              val listShapeName = outputMembersWithListType.values.head.getShape
              for {
                listModel <- context.get(listShapeName)
                listShape = listModel.shape
                itemShapeName = listShape.getListMember.getShape
                itemModel <- context.get(itemShapeName)
              } yield Some(
                ListPaginationDefinition(
                  memberName,
                  listModel,
                  itemModel,
                  isSimple
                )
              )
            } else if (outputMembersWithMapType.size == 1) {
              val memberName = outputMembersWithMapType.keys.head
              val mapShapeName = outputMembersWithMapType.values.head.getShape
              for {
                mapModel <- context.get(mapShapeName)
                mapShape = mapModel.shape
                keyModel <- context.get(mapShape.getMapKeyType.getShape)
                valueModel <- context.get(mapShape.getMapValueType.getShape)
              } yield Some(
                MapPaginationDefinition(
                  memberName,
                  mapModel,
                  keyModel,
                  valueModel,
                  isSimple
                )
              )
            } else if (outputMembersWithStringType.size == 1) {
              val memberName = outputMembersWithStringType.keys.head
              val stringShapeName =
                outputMembersWithStringType.values.head.getShape
              for {
                stringModel <- context.get(stringShapeName)
              } yield Some(
                StringPaginationDefinition(memberName, stringModel, isSimple)
              )
            } else {
              // Fall back to Java SDK paginator if possible
              getJavaSdkPaginatorDefinition(opName, op, models) match {
                case Some(definition) =>
                  definition.map(Some(_))
                case None =>
                  ZIO.fail(InvalidPaginatedOperation(id.toString, opName))
              }
            }
        }
      }
    }
  }

  private def getJavaSdkPaginatorDefinition(
      opName: String,
      op: Operation,
      models: C2jModels
  ) = {
    for {
      paginator <- Option(
        models.paginatorsModel().getPaginatorDefinition(opName)
      )
      if paginator.isValid
      key <- Option(paginator.getResultKey).flatMap(_.asScala.headOption)
      outputShape = models.serviceModel().getShape(op.getOutput.getShape)
      outputListMember <- outputShape.getMembers.asScala.get(key)
      listShape = models.serviceModel().getShape(outputListMember.getShape)
      itemMember <- Option(listShape.getListMember)
    } yield for {
      itemModel <- context.get(itemMember.getShape)
      itemType <- toJavaType(itemModel)
      wrappedTypeRo <- toWrappedTypeReadOnly(itemModel)
    } yield JavaSdkPaginationDefinition(
      name = key,
      model = itemModel,
      itemType = itemType,
      wrappedTypeRo = wrappedTypeRo
    )
  }

  private def isExcluded(
      customizationConfig: CustomizationConfig,
      opName: String
  ): Boolean =
    Option(customizationConfig.getOperationModifiers)
      .flatMap(_.asScala.get(opName))
      .exists(_.isExclude)

  private def hasStreamingMember(
      models: C2jModels,
      shape: Shape,
      alreadyChecked: Set[Shape] = Set.empty
  ): Boolean =
    if (alreadyChecked(shape)) {
      false
    } else {
      shape.isStreaming || shape.getMembers.asScala.values.exists { member =>
        member.isStreaming || hasStreamingMember(
          models,
          models.serviceModel().getShape(member.getShape),
          alreadyChecked + shape
        )
      }
    }

  private def hasEventStreamMember(
      models: C2jModels,
      shape: Shape,
      alreadyChecked: Set[Shape] = Set.empty
  ): Boolean =
    if (alreadyChecked(shape)) {
      false
    } else {
      shape.isEventstream || shape.getMembers.asScala.values.exists { member =>
        hasEventStreamMember(
          models,
          models.serviceModel().getShape(member.getShape),
          alreadyChecked + shape
        )
      }
    }

  sealed trait PaginationOverride {
    def toKey: OverrideKey
  }

  case class PaginationNotSupported(id: loader.ModelId, opName: String)
      extends PaginationOverride {
    override def toKey: OverrideKey = OverrideKey(id, opName)
  }

  case class SelectPaginatedListMember(
      id: loader.ModelId,
      opName: String,
      memberName: String
  ) extends PaginationOverride {
    override def toKey: OverrideKey = OverrideKey(id, opName)
  }

  case class SelectPaginatedStringMember(
      id: loader.ModelId,
      opName: String,
      memberName: String
  ) extends PaginationOverride {
    override def toKey: OverrideKey = OverrideKey(id, opName)
  }

}
