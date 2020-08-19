package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.generator.TypeMapping.{toJavaType, toWrappedTypeReadOnly}
import io.github.vigoo.zioaws.codegen.generator.context._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import zio.ZIO

import scala.jdk.CollectionConverters._

object OperationCollector {
  private def isExcluded(customizationConfig: CustomizationConfig, opName: String): Boolean =
    Option(customizationConfig.getOperationModifiers)
      .flatMap(_.asScala.get(opName))
      .exists(_.isExclude)

  def getFilteredOperations(models: C2jModels): Map[String, Operation] =
    models.serviceModel().getOperations.asScala
      .toMap
      .filter { case (_, op) => !op.isDeprecated }
      .filter { case (opName, _) => !isExcluded(models.customizationConfig(), opName) }

  private def hasStreamingMember(models: C2jModels, shape: Shape, alreadyChecked: Set[Shape] = Set.empty): Boolean =
    if (alreadyChecked(shape)) {
      false
    } else {
      shape.isStreaming || shape.getMembers.asScala.values.exists { member =>
        member.isStreaming || hasStreamingMember(models, models.serviceModel().getShape(member.getShape), alreadyChecked + shape)
      }
    }

  private def hasEventStreamMember(models: C2jModels, shape: Shape, alreadyChecked: Set[Shape] = Set.empty): Boolean =
    if (alreadyChecked(shape)) {
      false
    } else {
      shape.isEventStream || shape.getMembers.asScala.values.exists { member =>
        hasEventStreamMember(models, models.serviceModel().getShape(member.getShape), alreadyChecked + shape)
      }
    }

  def inputIsStreamingOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getInput).flatMap(input => Option(models.serviceModel().getShape(input.getShape))).exists(hasStreamingMember(models, _))

  def outputIsStreamingOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getOutput).flatMap(output => Option(models.serviceModel().getShape(output.getShape))).exists(hasStreamingMember(models, _))

  def inputIsEventStreamOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getInput).flatMap(input => Option(models.serviceModel().getShape(input.getShape))).exists(hasEventStreamMember(models, _))

  def outputIsEventStreamOf(models: C2jModels, op: Operation): Boolean =
    Option(op.getOutput).flatMap(output => Option(models.serviceModel().getShape(output.getShape))).exists(hasEventStreamMember(models, _))

  def get(opName: String, op: Operation): ZIO[GeneratorContext, GeneratorFailure, OperationMethodType] = {
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
          Option(models.paginatorsModel().getPaginatorDefinition(opName)) match {
            case Some(paginator) if paginator.isValid =>
              Option(paginator.getResultKey).flatMap(_.asScala.headOption) match {
                case Some(key) =>
                  val outputShape = models.serviceModel().getShape(op.getOutput.getShape)
                  outputShape.getMembers.asScala.get(key) match {
                    case Some(outputListMember) =>
                      val listShape = models.serviceModel().getShape(outputListMember.getShape)
                      Option(listShape.getListMember) match {
                        case Some(itemMember) =>
                          for {
                            itemModel <- context.get(itemMember.getShape)
                            itemType <- toJavaType(itemModel)
                            wrappedTypeRo <- toWrappedTypeReadOnly(itemModel)
                          } yield RequestResponse(pagination = Some(PaginationDefinition(
                            name = key,
                            model = itemModel,
                            itemType = itemType,
                            wrappedTypeRo = wrappedTypeRo
                          )))
                        case None =>
                          ZIO.succeed(RequestResponse(pagination = None))
                      }
                    case None =>
                      ZIO.succeed(RequestResponse(pagination = None))
                  }
                case None =>
                  ZIO.succeed(RequestResponse(pagination = None))
              }
            case _ =>
              if (op.getOutput == null && op.getInput == null) {
                ZIO.succeed(UnitToUnit)
              } else if (op.getOutput == null) {
                ZIO.succeed(RequestToUnit)
              } else if (op.getInput == null) {
                ZIO.succeed(UnitToResponse)
              } else {

                val outputShape = models.serviceModel().getShape(op.getOutput.getShape)
                val inputShape = models.serviceModel().getShape(op.getInput.getShape)

                if (outputShape.getMembers.containsKey("NextToken") &&
                  inputShape.getMembers.containsKey("NextToken")) {
                  // TODO: custom pagination
                  ZIO.succeed(RequestResponse(pagination = None))
                } else {
                  ZIO.succeed(RequestResponse(pagination = None))
                }
              }
          }
        }
      }
    }
  }
}
