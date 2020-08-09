package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.TypeMapping.toType

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import zio.{ZIO, console}
import zio.console.Console

import scala.meta.Term

sealed trait OperationMethodType

case class RequestResponse(pagination: Option[PaginationDefinition]) extends OperationMethodType
case object StreamedInput extends OperationMethodType
case object StreamedOutput extends OperationMethodType
case object StreamedInputOutput extends OperationMethodType
case object EventStreamInput extends OperationMethodType
case object EventStreamOutput extends OperationMethodType
case object EventStreamInputOutput extends OperationMethodType

object OperationMethodType {
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

  def get(models: C2jModels, modelMap: Map[String, Model], modelPkg: Term.Ref, opName: String, op: Operation): ZIO[Console, Nothing, OperationMethodType] = {
    val inputIsStreaming = Option(op.getInput).flatMap(input => Option(models.serviceModel().getShape(input.getShape))).exists(hasStreamingMember(models, _))
    val outputIsStreaming = Option(op.getOutput).flatMap(output => Option(models.serviceModel().getShape(output.getShape))).exists(hasStreamingMember(models, _))

    val inputIsEventStream = Option(op.getInput).flatMap(input => Option(models.serviceModel().getShape(input.getShape))).exists(hasEventStreamMember(models, _))
    val outputIsEventStream = Option(op.getOutput).flatMap(output => Option(models.serviceModel().getShape(output.getShape))).exists(hasEventStreamMember(models, _))

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
                      ZIO.succeed(
                        RequestResponse(pagination = Some(PaginationDefinition(
                          name = key,
                          itemType = toType(modelMap(itemMember.getShape.capitalize), modelMap, modelPkg)
                        ))))
                    case None =>
                      for {
                        _ <- console.putStrLnErr(s"Could not find list item member for ${outputListMember.getShape}")
                      } yield RequestResponse(pagination = None)
                  }
                case None =>
                  for {
                    _ <- console.putStrLnErr(s"Could not find output shape ${op.getOutput.getShape}")
                  } yield RequestResponse(pagination = None)
              }
            case None =>
              ZIO.succeed(RequestResponse(pagination = None))
          }
        case _ =>
          ZIO.succeed(RequestResponse(pagination = None))
      }
    }
  }
}