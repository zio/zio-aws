package io.github.vigoo.zioaws.codegen.generator

import java.io.File
import java.nio.charset.StandardCharsets

import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.generator.syntax._
import software.amazon.awssdk.codegen.model.service.Operation
import zio.ZIO
import zio.blocking.Blocking
import zio.nio.file.Files

import scala.jdk.CollectionConverters._
import scala.meta._

trait ServiceInterfaceGenerator {
  this: HasConfig with GeneratorBase =>

  private def opMethodName(opName: String): Term.Name =
    Term.Name(opName.toCamelCase)

  private def serviceName: ZIO[GeneratorContext, Nothing, Type.Name] =
    getNamingStrategy.map { namingStrategy => Type.Name(namingStrategy.getServiceName) }

  private def opRequestName(opName: String): ZIO[GeneratorContext, Nothing, Type.Name] =
    getNamingStrategy.map { namingStrategy => Type.Name(namingStrategy.getRequestClassName(opName)) }

  private def opRequestNameTerm(opName: String): ZIO[GeneratorContext, Nothing, Term.Name] =
    getNamingStrategy.map { namingStrategy => Term.Name(namingStrategy.getRequestClassName(opName)) }

  private def opResponseName(opName: String): ZIO[GeneratorContext, Nothing, Type.Name] =
    getNamingStrategy.map { namingStrategy => Type.Name(namingStrategy.getResponseClassName(opName)) }

  private def opResponseNameTerm(opName: String): ZIO[GeneratorContext, Nothing, Term.Name] =
    getNamingStrategy.map { namingStrategy => Term.Name(namingStrategy.getResponseClassName(opName)) }

  private def findEventStreamShape(outputShape: String): ZIO[GeneratorContext, GeneratorFailure, NamedShape] =
    getModels.flatMap { models =>
      ModelCollector.tryFindEventStreamShape(models, outputShape) match {
        case Some(value) => ZIO.succeed(value)
        case None => getServiceName.flatMap(serviceName => ZIO.fail(CannotFindEventStreamInShape(serviceName, outputShape)))
      }
    }

  private def generateServiceMethods(): ZIO[GeneratorContext, GeneratorFailure, List[ServiceMethods]] = {
    getModels.flatMap { models =>
      val ops = OperationCollector.getFilteredOperations(models)
      ZIO.foreach(ops.toList) { case (opName, op) =>
        for {
          serviceNameT <- serviceName
          methodName = opMethodName(opName)
          requestName <- opRequestName(opName)
          requestNameTerm <- opRequestNameTerm(opName)
          responseName <- opResponseName(opName)
          responseNameTerm <- opResponseNameTerm(opName)
          responseTypeRo = Type.Select(responseNameTerm, Type.Name("ReadOnly"))
          modelPkg <- getModelPkg
          operation <- OperationCollector.get(opName, op)
          result <- operation match {
            case UnitToUnit =>
              generateUnitToUnit(serviceNameT, methodName, requestName, requestNameTerm, responseName, modelPkg)
            case UnitToResponse =>
              generateUnitToResponse(serviceNameT, methodName, requestName, requestNameTerm, responseName, responseNameTerm, responseTypeRo, modelPkg)
            case RequestToUnit =>
              generateRequestToUnit(serviceNameT, methodName, requestName, responseName, modelPkg)
            case RequestResponse(pagination) =>
              generateRequestToResponse(opName, serviceNameT, methodName, requestName, responseName, responseNameTerm, responseTypeRo, modelPkg, pagination)
            case StreamedInput =>
              generateStreamedInput(serviceNameT, methodName, requestName, responseName, responseNameTerm, responseTypeRo, modelPkg)
            case StreamedOutput =>
              generateStreamedOutput(serviceNameT, methodName, requestName, responseName, responseNameTerm, responseTypeRo, modelPkg)
            case StreamedInputOutput =>
              generateStreamedInputOutput(serviceNameT, methodName, requestName, responseName, responseNameTerm, responseTypeRo, modelPkg)
            case EventStreamInput =>
              generateEventStreamInput(op, serviceNameT, methodName, requestName, responseName, responseTypeRo, modelPkg)
            case EventStreamOutput =>
              generateEventStreamOutput(opName, op, serviceNameT, methodName, requestName, responseName, modelPkg)
            case EventStreamInputOutput =>
              generateEventStreamInputOutput(opName, op, serviceNameT, methodName, requestName, responseName, modelPkg)
          }
        } yield result
      }
    }
  }

  private def generateEventStreamInputOutput(opName: String, op: Operation, serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, modelPkg: Term.Ref) = {
    for {
      inputEventStream <- findEventStreamShape(op.getInput.getShape)
      awsInEventStreamT = Type.Select(modelPkg, Type.Name(inputEventStream.name))
      inEventShapeName = inputEventStream.shape.getMembers.asScala.keys.head
      inEventT <- get(inEventShapeName).map(_.asType)

      outputEventStream <- findEventStreamShape(op.getOutput.getShape)
      outEventShapeName = outputEventStream.shape.getMembers.asScala.keys.head
      outEventModel <- get(outEventShapeName)
      awsOutEventT <- TypeMapping.toJavaType(outEventModel)

      awsOutEventStreamT = Type.Select(modelPkg, Type.Name(outputEventStream.name))
      outEventRoT = Type.Select(Term.Name(outEventModel.name), Type.Name("ReadOnly"))
      responseHandlerName = opName + "ResponseHandler"
      responseHandlerT = Type.Select(modelPkg, Type.Name(responseHandlerName))
      responseHandlerInit = Init(responseHandlerT, Name.Anonymous(), List.empty)

      wrappedItem <- wrapSdkValue(outEventModel, Term.Name("item"))
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): zio.stream.ZStream[Any, AwsError, $outEventRoT]""",
        implementation =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): zio.stream.ZStream[Any, AwsError, $outEventRoT] =
                  asyncRequestEventInputOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $awsInEventStreamT, $responseHandlerT, $awsOutEventStreamT, $awsOutEventT](
                    (request: $modelPkg.$requestName, input: Publisher[$awsInEventStreamT], handler: $responseHandlerT) => api.$methodName(request, input, handler),
                    (impl: software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler[$modelPkg.$responseName, $awsOutEventStreamT]) =>
                      new $responseHandlerInit {
                        override def responseReceived(response: $modelPkg.$responseName): Unit = impl.responseReceived(response)
                        override def onEventStream(publisher: software.amazon.awssdk.core.async.SdkPublisher[$awsOutEventStreamT]): Unit = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: Throwable): Unit = impl.exceptionOccurred(throwable)
                        override def complete(): Unit = impl.complete()
                      })(request.buildAwsValue(), input.map(_.buildAwsValue())).map(item => $wrappedItem).provide(r)
               """,
        accessor =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): zio.stream.ZStream[$serviceNameT, AwsError, $outEventRoT] =
                ZStream.accessStream(_.get.$methodName(request, input))"""
      ))
  }

  private def generateEventStreamOutput(opName: String, op: Operation, serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, modelPkg: Term.Ref) = {
    for {
      eventStream <- findEventStreamShape(op.getOutput.getShape)
      awsEventStreamT = Type.Select(modelPkg, Type.Name(eventStream.name))
      rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
      eventItemModel <- get(rawEventItemName)
      eventRoT = Type.Select(Term.Name(eventItemModel.name), Type.Name("ReadOnly"))
      awsEventT <- TypeMapping.toJavaType(eventItemModel)
      responseHandlerName = opName + "ResponseHandler"
      responseHandlerT = Type.Select(modelPkg, Type.Name(responseHandlerName))

      responseHandlerInit = Init(responseHandlerT, Name.Anonymous(), List.empty)

      wrappedItem <- wrapSdkValue(eventItemModel, Term.Name("item"))
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName): zio.stream.ZStream[Any, AwsError, $eventRoT]""",
        implementation =
          q"""def $methodName(request: $requestName): zio.stream.ZStream[Any, AwsError, $eventRoT] =
                  asyncRequestEventOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $responseHandlerT, $awsEventStreamT, $awsEventT](
                    (request: $modelPkg.$requestName, handler: $responseHandlerT) => api.$methodName(request, handler),
                    (impl: software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler[$modelPkg.$responseName, $awsEventStreamT]) =>
                      new $responseHandlerInit {
                        override def responseReceived(response: $modelPkg.$responseName): Unit = impl.responseReceived(response)
                        override def onEventStream(publisher: software.amazon.awssdk.core.async.SdkPublisher[$awsEventStreamT]): Unit = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: Throwable): Unit = impl.exceptionOccurred(throwable)
                        override def complete(): Unit = impl.complete()
                      })(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
              """,
        accessor =
          q"""def $methodName(request: $requestName): zio.stream.ZStream[$serviceNameT, AwsError, $eventRoT] =
                ZStream.accessStream(_.get.$methodName(request))"""
      ))
  }

  private def generateEventStreamInput(op: Operation, serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    for {
      eventStream <- findEventStreamShape(op.getInput.getShape)
      awsInEventStreamT = Type.Select(modelPkg, Type.Name(eventStream.name))
      rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
      eventT <- get(rawEventItemName).map(_.asType)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $eventT]): IO[AwsError, $responseTypeRo]""",
        implementation =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $eventT]): IO[AwsError, $responseTypeRo] =
                asyncRequestEventInputStream[$modelPkg.$requestName, $modelPkg.$responseName, $awsInEventStreamT](api.$methodName)(request.buildAwsValue(), input.map(_.buildAwsValue())).provide(r)""",
        accessor =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $eventT]): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName(request, input))"""))
  }

  private def generateStreamedInputOutput(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]]""",
        implementation =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                asyncRequestInputOutputStream[$modelPkg.$requestName, $modelPkg.$responseName](
                  api.$methodName[zio.Task[StreamingOutputResult[Any, $modelPkg.$responseName, Byte]]]
                )(request.buildAwsValue(), body)
                  .map(_.mapResponse($responseNameTerm.wrap).provide(r))
                  .provide(r)
          """,
        accessor =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Byte]): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                ZIO.accessM(_.get.$methodName(request, body))"""
      )))
  }

  private def generateStreamedOutput(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]]""",
        implementation =
          q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                asyncRequestOutputStream[$modelPkg.$requestName, $modelPkg.$responseName](
                  api.$methodName[zio.Task[StreamingOutputResult[Any, $modelPkg.$responseName, Byte]]]
                )(request.buildAwsValue())
                  .map(_.mapResponse($responseNameTerm.wrap).provide(r))
                  .provide(r)""",
        accessor =
          q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                ZIO.accessM(_.get.$methodName(request))"""
      )))
  }

  private def generateStreamedInput(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, $responseTypeRo]""",
        implementation =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, $responseTypeRo] =
                asyncRequestInputStream[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)(request.buildAwsValue(), body).map($responseNameTerm.wrap).provide(r)""",
        accessor =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Byte]): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName(request, body))"""
      )))
  }

  private def generateRequestToResponse(opName: String, serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref, pagination: Option[PaginationDefinition]) = {
    pagination match {
      case Some(JavaSdkPaginationDefinition(paginationName, itemModel, _, wrappedItemType)) =>
        for {
          paginatorPkg <- getPaginatorPkg
          paginatorMethodName = Term.Name(methodName.value + "Paginator")
          publisherType = Type.Name(opName + "Publisher")
          wrappedItem <- wrapSdkValue(itemModel, Term.Name("item"))
          awsItemType <- TypeMapping.toJavaType(itemModel)
          streamedPaginator = ServiceMethod(
            interface =
              q"""def $methodName(request: $requestName): zio.stream.ZStream[Any, AwsError, $wrappedItemType]""",
            implementation =
              q"""def $methodName(request: $requestName): zio.stream.ZStream[Any, AwsError, $wrappedItemType] =
                    asyncJavaPaginatedRequest[$modelPkg.$requestName, $awsItemType, $paginatorPkg.$publisherType](api.$paginatorMethodName, _.${Term.Name(paginationName.uncapitalize)}())(request.buildAwsValue()).map(item => $wrappedItem).provide(r)""",
            accessor =
              q"""def $methodName(request: $requestName): zio.stream.ZStream[$serviceNameT, AwsError, $wrappedItemType] =
                    ZStream.accessStream(_.get.$methodName(request))"""
          )
        } yield ServiceMethods(streamedPaginator)
      case Some(ListPaginationDefinition(memberName, listModel, itemModel, isSimple)) =>
        for {
          wrappedItem <- wrapSdkValue(itemModel, Term.Name("item"))
          itemTypeRo <- TypeMapping.toWrappedTypeReadOnly(itemModel)
          awsItemType <- TypeMapping.toJavaType(itemModel)
          responseModel <- context.get(responseName.value)
          property <- propertyName(responseModel, listModel, memberName)
          propertyNameTerm = Term.Name(property)
          streamedPaginator = if (isSimple) {
            ServiceMethod(
              interface =
                q"""def $methodName(request: $requestName): ZStream[Any, AwsError, $itemTypeRo]""",
              implementation =
                q"""def $methodName(request: $requestName): ZStream[Any, AwsError, $itemTypeRo] =
                    asyncSimplePaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
               """,
              accessor =
                q"""def $methodName(request: $requestName): ZStream[$serviceNameT, AwsError, $itemTypeRo] =
                    ZStream.accessStream(_.get.$methodName(request))
               """
            )
          } else {
            ServiceMethod(
              interface =
                q"""def $methodName(request: $requestName): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]]""",
              implementation =
                q"""def $methodName(request: $requestName): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    asyncPaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map(result =>
                         result
                           .mapResponse($responseNameTerm.wrap)
                           .mapOutput(_.map(item => $wrappedItem))
                           .provide(r))
                      .provide(r)
               """,
              accessor =
                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    ZIO.accessM(_.get.$methodName(request))
               """
            )
          }
        } yield ServiceMethods(streamedPaginator)
      case Some(MapPaginationDefinition(memberName, mapModel, keyModel, valueModel, isSimple)) =>
        for {
          wrappedKey <- wrapSdkValue(keyModel, Term.Name("key"))
          keyTypeRo <- TypeMapping.toWrappedTypeReadOnly(keyModel)
          awsKeyType <- TypeMapping.toJavaType(keyModel)
          wrappedValue <- wrapSdkValue(valueModel, Term.Name("value"))
          valueTypeRo <- TypeMapping.toWrappedTypeReadOnly(valueModel)
          awsValueType <- TypeMapping.toJavaType(valueModel)

          responseModel <- context.get(responseName.value)
          property <- propertyName(responseModel, mapModel, memberName)
          propertyNameTerm = Term.Name(property)
          streamedPaginator = if (isSimple) {
            ServiceMethod(
              interface =
                q"""def $methodName(request: $requestName): ZStream[Any, AwsError, ($keyTypeRo, $valueTypeRo)]""",
              implementation =
                q"""def $methodName(request: $requestName): ZStream[Any, AwsError, ($keyTypeRo, $valueTypeRo)] =
                    asyncSimplePaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, ($awsKeyType, $awsValueType)](
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map { case (key, value) => $wrappedKey -> $wrappedValue }.provide(r)
               """,
              accessor =
                q"""def $methodName(request: $requestName): ZStream[$serviceNameT, AwsError, ($keyTypeRo, $valueTypeRo)] =
                    ZStream.accessStream(_.get.$methodName(request))
               """
            )
          } else {
            ServiceMethod(
              interface =
                q"""def $methodName(request: $requestName): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]]""",
              implementation =
                q"""def $methodName(request: $requestName): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]] =
                    asyncPaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, ($awsKeyType, $awsValueType)](
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map { result =>
                        result
                          .mapResponse($responseNameTerm.wrap)
                          .mapOutput(_.map { case (key, value) => $wrappedKey -> $wrappedValue })
                          .provide(r)
                      }.provide(r)
               """,
              accessor =
                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]] =
                    ZIO.accessM(_.get.$methodName(request))
               """
            )
          }
        } yield ServiceMethods(streamedPaginator)
      case Some(StringPaginationDefinition(memberName, stringModel, isSimple)) =>
        for {
          wrappedItem <- wrapSdkValue(stringModel, Term.Name("item"))
          itemTypeRo <- TypeMapping.toWrappedTypeReadOnly(stringModel)
          awsItemType <- TypeMapping.toJavaType(stringModel)
          responseModel <- context.get(responseName.value)
          property <- propertyName(responseModel, stringModel, memberName)
          propertyNameTerm = Term.Name(property)
          streamedPaginator = if (isSimple) {
            ServiceMethod(
              interface =
                q"""def $methodName(request: $requestName): ZStream[Any, AwsError, $itemTypeRo]""",
              implementation =
                q"""def $methodName(request: $requestName): ZStream[Any, AwsError, $itemTypeRo] =
                    asyncSimplePaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk(r.$propertyNameTerm())
                    )(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
               """,
              accessor =
                q"""def $methodName(request: $requestName): ZStream[$serviceNameT, AwsError, $itemTypeRo] =
                    ZStream.accessStream(_.get.$methodName(request))
               """
            )
          } else {
            ServiceMethod(
              interface =
                q"""def $methodName(request: $requestName): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]]""",
              implementation =
                q"""def $methodName(request: $requestName): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    asyncPaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk(r.$propertyNameTerm())
                    )(request.buildAwsValue())
                      .map(result =>
                        result
                          .mapResponse($responseNameTerm.wrap)
                          .mapOutput(_.map(item => $wrappedItem))
                          .provide(r))
                      .provide(r)
               """,
              accessor =
                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    ZIO.accessM(_.get.$methodName(request))
               """
            )
          }
        } yield ServiceMethods(streamedPaginator)
      case None =>
        ZIO.succeed(ServiceMethods(ServiceMethod(
          interface =
            q"""def $methodName(request: $requestName): IO[AwsError, $responseTypeRo]""",
          implementation =
            q"""def $methodName(request: $requestName): IO[AwsError, $responseTypeRo] =
              asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)(request.buildAwsValue()).map($responseNameTerm.wrap).provide(r)""",
          accessor =
            q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
              ZIO.accessM(_.get.$methodName(request))"""
        )))
    }
  }

  private def generateRequestToUnit(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName): IO[AwsError, scala.Unit]""",
        implementation =
          q"""def $methodName(request: $requestName): IO[AwsError, scala.Unit] =
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)(request.buildAwsValue()).unit.provide(r)""",
        accessor =
          q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, scala.Unit] =
                ZIO.accessM(_.get.$methodName(request))"""
      )
    ))
  }

  private def generateUnitToResponse(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, requestNameTerm: Term.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(): IO[AwsError, $responseTypeRo]""",
        implementation =
          q"""def $methodName(): IO[AwsError, $responseTypeRo] =
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)($modelPkg.$requestNameTerm.builder().build()).map($responseNameTerm.wrap).provide(r)""",
        accessor =
          q"""def $methodName(): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName())"""
      )
    ))
  }

  private def generateUnitToUnit(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, requestNameTerm: Term.Name, responseName: Type.Name, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(): IO[AwsError, scala.Unit]""",
        implementation =
          q"""def $methodName(): IO[AwsError, scala.Unit] =
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)($modelPkg.$requestNameTerm.builder().build()).unit.provide(r)""",
        accessor =
          q"""def $methodName(): ZIO[$serviceNameT, AwsError, scala.Unit] =
                ZIO.accessM(_.get.$methodName())"""
      )
    ))
  }

  private def generateServiceModuleCode(): ZIO[GeneratorContext, GeneratorFailure, String] =
    for {
      id <- getService
      namingStrategy <- getNamingStrategy
      paginatorPackage <- getPaginatorPkg
      pkgName = Term.Name(id.moduleName)
      serviceName = Term.Name(namingStrategy.getServiceName)
      serviceImplName = Term.Name(serviceName.value + "Impl")
      serviceImplT = Type.Name(serviceImplName.value)
      serviceNameT = Type.Name(serviceName.value)
      serviceTrait = Type.Select(serviceName, Type.Name("Service"))
      clientInterface = Type.Name(serviceName.value + "AsyncClient")
      clientInterfaceSingleton = Term.Name(serviceName.value + "AsyncClient")
      clientInterfaceBuilder = Type.Name(serviceName.value + "AsyncClientBuilder")

      serviceMethods <- generateServiceMethods()
      ops <- awsModel.getOperations
      javaSdkPaginations <- ZIO.foreach(ops) { case (opName, op) =>
        OperationCollector.get(opName, op).map {
          case RequestResponse(Some(JavaSdkPaginationDefinition(_, _, _, _))) => true
          case _ => false
        }
      }
      usesJavaSdkPaginators = javaSdkPaginations.exists(identity)

      serviceMethodIfaces = serviceMethods.flatMap(_.methods.map(_.interface))
      serviceMethodImpls = serviceMethods.flatMap(_.methods.map(_.implementation))
      serviceAccessors = serviceMethods.flatMap(_.methods.map(_.accessor))

      imports = List(
        Some(q"""import io.github.vigoo.zioaws.core._"""),
        Some(q"""import io.github.vigoo.zioaws.core.config.AwsConfig"""),
        if (usesJavaSdkPaginators)
          Some(Import(List(Importer(paginatorPackage, List(Importee.Wildcard())))))
        else None,
        Some(id.subModuleName match {
          case Some(submodule) if submodule != id.name =>
            Import(List(Importer(q"software.amazon.awssdk.services.${Term.Name(id.name)}.${Term.Name(submodule)}",
              List(
                Importee.Name(Name(clientInterface.value)),
                Importee.Name(Name(clientInterfaceBuilder.value))))))
          case _ =>
            Import(List(Importer(q"software.amazon.awssdk.services.$pkgName",
              List(
                Importee.Name(Name(clientInterface.value)),
                Importee.Name(Name(clientInterfaceBuilder.value))))))
        }),
        Some(q"""import zio.{Chunk, Has, IO, ZIO, ZLayer}"""),
        Some(q"""import zio.stream.ZStream"""),
        Some(q"""import org.reactivestreams.Publisher"""),
        Some(q"""import scala.jdk.CollectionConverters._""")
      ).flatten

      module =
      q"""
        package object $pkgName {
          import model._

          type $serviceNameT = Has[$serviceTrait]

          object $serviceName {
            trait Service {
              val api: $clientInterface

              ..$serviceMethodIfaces
            }
          }

          val live: ZLayer[AwsConfig, Throwable, $serviceNameT] = customized(identity)

          def customized(customization: $clientInterfaceBuilder => $clientInterfaceBuilder): ZLayer[AwsConfig, Throwable, $serviceNameT] =
            (for {
              awsConfig <- ZIO.service[AwsConfig.Service]
              b0 <- awsConfig.configure[$clientInterface, $clientInterfaceBuilder]($clientInterfaceSingleton.builder())
              b1 <- awsConfig.configureHttpClient[$clientInterface, $clientInterfaceBuilder](b0)
              client <- ZIO(customization(b1).build())
            } yield new $serviceImplT(client, Aspect.identity, ().asInstanceOf[Any])).toLayer

          private class $serviceImplT[R](override val api: $clientInterface, override val aspect: Aspect[R, AwsError], r: R)
            extends ${Init(serviceTrait, Name.Anonymous(), List.empty)} with AwsServiceBase[R, $serviceImplT] {
              override def withAspect[R1 <: R](newAspect: Aspect[R1, AwsError], r: R1): $serviceImplT[R1] =
                new $serviceImplT(api, aspect, r)

              ..$serviceMethodImpls
            }

          ..$serviceAccessors
        }
      """
      pkg =
      q"""package io.github.vigoo.zioaws {
              ..$imports
              $module
            }
          """
    } yield pkg.toString

  protected def generateServiceModule(): ZIO[GeneratorContext with Blocking, GeneratorFailure, File] =
    for {
      code <- generateServiceModuleCode()
      id <- getService
      moduleName = id.moduleName
      targetRoot = config.targetRoot
      packageParent = targetRoot / "io/github/vigoo/zioaws"
      packageRoot = packageParent / moduleName
      moduleFile = packageRoot / "package.scala"
      _ <- Files.createDirectories(packageRoot).mapError(FailedToCreateDirectories)
      _ <- writeIfDifferent(moduleFile, code)
    } yield moduleFile.toFile

}
