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
    getNamingStrategy.map { namingStrategy =>
      Type.Name(namingStrategy.getServiceName)
    }

  private def opRequestName(
      opName: String
  ): ZIO[GeneratorContext, Nothing, Type.Name] =
    getNamingStrategy.map { namingStrategy =>
      Type.Name(namingStrategy.getRequestClassName(opName))
    }

  private def opRequestNameTerm(
      opName: String
  ): ZIO[GeneratorContext, Nothing, Term.Name] =
    getNamingStrategy.map { namingStrategy =>
      Term.Name(namingStrategy.getRequestClassName(opName))
    }

  private def opResponseName(
      opName: String
  ): ZIO[GeneratorContext, Nothing, Type.Name] =
    getNamingStrategy.map { namingStrategy =>
      Type.Name(namingStrategy.getResponseClassName(opName))
    }

  private def opResponseNameTerm(
      opName: String
  ): ZIO[GeneratorContext, Nothing, Term.Name] =
    getNamingStrategy.map { namingStrategy =>
      Term.Name(namingStrategy.getResponseClassName(opName))
    }

  private def findEventStreamShape(
      outputShape: String
  ): ZIO[GeneratorContext, GeneratorFailure, NamedShape] =
    getModels.flatMap { models =>
      ModelCollector.tryFindEventStreamShape(models, outputShape) match {
        case Some(value) => ZIO.succeed(value)
        case None =>
          getServiceName.flatMap(serviceName =>
            ZIO.fail(CannotFindEventStreamInShape(serviceName, outputShape))
          )
      }
    }

  private def inModel(t: Type): Type = 
    t match {
      case Type.Name(name) if TypeMapping.isBuiltIn(name) => t
      case name@Type.Name(_) => Type.Select(Term.Name("model"), name)
      case Type.Select(Term.Name(name1), name2) => Type.Select(Term.Select(Term.Name("model"), Term.Name(name1)), name2)
      case t"List[$item]" => t"List[${inModel(item)}]"
      case t"Map[$key, $value]" => t"Map[${inModel(key)}, ${inModel(value)}]"
      case _ => throw new RuntimeException(s"Cannot inModel() $t")
    }

  private def generateServiceMethods()
      : ZIO[GeneratorContext, GeneratorFailure, List[ServiceMethods]] = {
    getModels.flatMap { models =>
      val ops = OperationCollector.getFilteredOperations(models)
      ZIO.foreach(ops.toList) { case (opName, op) =>
        for {
          serviceNameT <- serviceName
          methodName = opMethodName(opName)
          requestName <- opRequestName(opName)
          requestType = Type.Select(Term.Name("model"), requestName)
          requestNameTerm <- opRequestNameTerm(opName)
          responseName <- opResponseName(opName)
          responseNameTerm <- opResponseNameTerm(opName)
          responseTypeRo =
            Type.Select(Term.Select(Term.Name("model"), responseNameTerm), Type.Name("ReadOnly"))
          modelPkg <- getModelPkg
          operation <- OperationCollector.get(opName, op)
          result <- operation match {
            case UnitToUnit =>
              generateUnitToUnit(
                serviceNameT,
                methodName,
                requestName,
                requestNameTerm,
                responseName,
                modelPkg
              )
            case UnitToResponse =>
              generateUnitToResponse(
                serviceNameT,
                methodName,
                requestName,
                requestNameTerm,
                responseName,
                responseNameTerm,
                responseTypeRo,
                modelPkg
              )
            case RequestToUnit =>
              generateRequestToUnit(
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                modelPkg
              )
            case RequestResponse(pagination) =>
              generateRequestToResponse(
                opName,
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                responseNameTerm,
                responseTypeRo,
                modelPkg,
                pagination
              )
            case StreamedInput =>
              generateStreamedInput(
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                responseNameTerm,
                responseTypeRo,
                modelPkg
              )
            case StreamedInputToUnit =>
              generateStreamedInputToUnit(
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                modelPkg
              )
            case StreamedOutput =>
              generateStreamedOutput(
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                responseNameTerm,
                responseTypeRo,
                modelPkg
              )
            case StreamedInputOutput =>
              generateStreamedInputOutput(
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                responseNameTerm,
                responseTypeRo,
                modelPkg
              )
            case EventStreamInput =>
              generateEventStreamInput(
                op,
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                responseTypeRo,
                modelPkg
              )
            case EventStreamOutput =>
              generateEventStreamOutput(
                opName,
                op,
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                modelPkg
              )
            case EventStreamInputOutput =>
              generateEventStreamInputOutput(
                opName,
                op,
                serviceNameT,
                methodName,
                requestName,
                requestType,
                responseName,
                modelPkg
              )
          }
        } yield result
      }
    }
  }

  private def generateEventStreamInputOutput(
      opName: String,
      op: Operation,
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      modelPkg: Term.Ref
  ) = {
    for {
      inputEventStream <- findEventStreamShape(op.getInput.getShape)
      awsInEventStreamT =
        Type.Select(modelPkg, Type.Name(inputEventStream.name))
      inEventShapeName = inputEventStream.shape.getMembers.asScala.keys.head
      inEventT <- get(inEventShapeName).map(_.asType).map(inModel)

      outputEventStream <- findEventStreamShape(op.getOutput.getShape)
      outEventShapeName = outputEventStream.shape.getMembers.asScala.keys.head
      outEventModel <- get(outEventShapeName)
      awsOutEventT <- TypeMapping.toJavaType(outEventModel)

      awsOutEventStreamT =
        Type.Select(modelPkg, Type.Name(outputEventStream.name))
      outEventRoT =
        Type.Select(Term.Select(Term.Name("model"), Term.Name(outEventModel.name)), Type.Name("ReadOnly"))
      responseHandlerName = opName + "ResponseHandler"
      responseHandlerT = Type.Select(modelPkg, Type.Name(responseHandlerName))
      responseHandlerInit = Init(responseHandlerT, Name.Anonymous(), List.empty)

      wrappedItem <- wrapSdkValue(outEventModel, Term.Name("item"), Term.Select(Term.Name("model"), _))
      opNameLit = Lit.String(opName)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $inEventT]): zio.stream.ZStream[Any, AwsError, $outEventRoT]""",
        implementation =
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $inEventT]): zio.stream.ZStream[Any, AwsError, $outEventRoT] =
                  asyncRequestEventInputOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $awsInEventStreamT, $responseHandlerT, $awsOutEventStreamT, $awsOutEventT](
                    $opNameLit,
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
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $inEventT]): zio.stream.ZStream[$serviceNameT, AwsError, $outEventRoT] =
                ZStream.accessStream(_.get.$methodName(request, input))""",
        mockObject =
          q"""object $objectName extends Stream[($requestType, zio.stream.ZStream[Any, AwsError, $inEventT]), AwsError, $outEventRoT]""",
        mockCompose =
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $inEventT]): zio.stream.ZStream[Any, AwsError, $outEventRoT] = rts.unsafeRun(proxy($objectName, request, input))"""
      )
    )
  }

  private def generateEventStreamOutput(
      opName: String,
      op: Operation,
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      modelPkg: Term.Ref
  ) = {
    for {
      eventStream <- findEventStreamShape(op.getOutput.getShape)
      awsEventStreamT = Type.Select(modelPkg, Type.Name(eventStream.name))
      rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
      eventItemModel <- get(rawEventItemName)
      eventRoT =
        Type.Select(Term.Select(Term.Name("model"), Term.Name(eventItemModel.name)), Type.Name("ReadOnly"))
      awsEventT <- TypeMapping.toJavaType(eventItemModel)
      responseHandlerName = opName + "ResponseHandler"
      responseHandlerT = Type.Select(modelPkg, Type.Name(responseHandlerName))

      responseHandlerInit = Init(responseHandlerT, Name.Anonymous(), List.empty)

      wrappedItem <- wrapSdkValue(eventItemModel, Term.Name("item"), Term.Select(Term.Name("model"), _))
      opNameLit = Lit.String(opName)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestType): zio.stream.ZStream[Any, AwsError, $eventRoT]""",
        implementation =
          q"""def $methodName(request: $requestType): zio.stream.ZStream[Any, AwsError, $eventRoT] =
                  asyncRequestEventOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $responseHandlerT, $awsEventStreamT, $awsEventT](
                    $opNameLit,
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
          q"""def $methodName(request: $requestType): zio.stream.ZStream[$serviceNameT, AwsError, $eventRoT] =
                ZStream.accessStream(_.get.$methodName(request))""",
        mockObject =
          q"""object $objectName extends Stream[$requestType, AwsError, $eventRoT]""",
        mockCompose =
          q"""def $methodName(request: $requestType): zio.stream.ZStream[Any, AwsError, $eventRoT] = rts.unsafeRun(proxy($objectName, request))"""
      )
    )
  }

  private def generateEventStreamInput(
      op: Operation,
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      responseTypeRo: Type.Select,
      modelPkg: Term.Ref
  ) = {
    for {
      eventStream <- findEventStreamShape(op.getInput.getShape)
      awsInEventStreamT = Type.Select(modelPkg, Type.Name(eventStream.name))
      rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
      eventT <- get(rawEventItemName).map(_.asType).map(inModel)
      opNameLit = Lit.String(methodName.value)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $eventT]): IO[AwsError, $responseTypeRo]""",
        implementation =
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $eventT]): IO[AwsError, $responseTypeRo] =
                asyncRequestEventInputStream[$modelPkg.$requestName, $modelPkg.$responseName, $awsInEventStreamT]($opNameLit, api.$methodName)(request.buildAwsValue(), input.map(_.buildAwsValue())).provide(r)""",
        accessor =
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $eventT]): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName(request, input))""",
        mockObject =
          q"""object $objectName extends Effect[($requestType, zio.stream.ZStream[Any, AwsError, $eventT]), AwsError, $responseTypeRo]""",
        mockCompose =
          q"""def $methodName(request: $requestType, input: zio.stream.ZStream[Any, AwsError, $eventT]): IO[AwsError, $responseTypeRo] = proxy($objectName, request, input)"""
      )
    )
  }

  private def generateStreamedInputOutput(
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      responseNameTerm: Term.Name,
      responseTypeRo: Type.Select,
      modelPkg: Term.Ref
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]]""",
          implementation =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                asyncRequestInputOutputStream[$modelPkg.$requestName, $modelPkg.$responseName](
                  ${Lit.String(methodName.value)},
                  api.$methodName[zio.Task[StreamingOutputResult[R, $modelPkg.$responseName, Byte]]]
                )(request.buildAwsValue(), body)
                  .map(_.mapResponse(model.$responseNameTerm.wrap).provide(r))
                  .provide(r)
          """,
          accessor =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                ZIO.accessM(_.get.$methodName(request, body))""",
          mockObject =
            q"""object $objectName extends Effect[($requestType, zio.stream.ZStream[Any, AwsError, Byte]), AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]]""",
          mockCompose =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] = proxy($objectName, request, body)"""
        )
      )
    )
  }

  private def generateStreamedOutput(
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      responseNameTerm: Term.Name,
      responseTypeRo: Type.Select,
      modelPkg: Term.Ref
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: $requestType): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]]""",
          implementation =
            q"""def $methodName(request: $requestType): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                asyncRequestOutputStream[$modelPkg.$requestName, $modelPkg.$responseName](
                  ${Lit.String(methodName.value)},
                  api.$methodName[zio.Task[StreamingOutputResult[R, $modelPkg.$responseName, Byte]]]
                )(request.buildAwsValue())
                  .map(_.mapResponse(model.$responseNameTerm.wrap).provide(r))
                  .provide(r)""",
          accessor =
            q"""def $methodName(request: $requestType): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] =
                ZIO.accessM(_.get.$methodName(request))""",
          mockObject =
            q"""object $objectName extends Effect[$requestType, AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]]""",
          mockCompose =
            q"""def $methodName(request: $requestType): IO[AwsError, StreamingOutputResult[Any, $responseTypeRo, Byte]] = proxy($objectName, request)"""          
        )
      )
    )
  }

  private def generateStreamedInput(
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      responseNameTerm: Term.Name,
      responseTypeRo: Type.Select,
      modelPkg: Term.Ref
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, $responseTypeRo]""",
          implementation =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, $responseTypeRo] =
                asyncRequestInputStream[$modelPkg.$requestName, $modelPkg.$responseName](${Lit
              .String(
                methodName.value
              )}, api.$methodName)(request.buildAwsValue(), body).map(model.$responseNameTerm.wrap).provide(r)""",
          accessor =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName(request, body))""",
          mockObject =
            q"""object $objectName extends Effect[($requestType, zio.stream.ZStream[Any, AwsError, Byte]), AwsError, $responseTypeRo]""",
          mockCompose =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, $responseTypeRo] = proxy($objectName, request, body)"""          
        )
      )
    )
  }

  private def generateStreamedInputToUnit(
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      modelPkg: Term.Ref
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, scala.Unit]""",
          implementation =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, scala.Unit] =
                asyncRequestInputStream[$modelPkg.$requestName, $modelPkg.$responseName](${Lit
              .String(
                methodName.value
              )}, api.$methodName)(request.buildAwsValue(), body).unit.provide(r)""",
          accessor =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): ZIO[$serviceNameT, AwsError, scala.Unit] =
                ZIO.accessM(_.get.$methodName(request, body))""",
          mockObject =
            q"""object $objectName extends Effect[($requestType, zio.stream.ZStream[Any, AwsError, Byte]), AwsError, scala.Unit]""",
          mockCompose =
            q"""def $methodName(request: $requestType, body: zio.stream.ZStream[Any, AwsError, Byte]): IO[AwsError, scala.Unit] = proxy($objectName, request, body)"""                    
        )
      )
    )
  }

  private def generateRequestToResponse(
      opName: String,
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      responseNameTerm: Term.Name,
      responseTypeRo: Type.Select,
      modelPkg: Term.Ref,
      pagination: Option[PaginationDefinition]
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    pagination match {
      case Some(
            JavaSdkPaginationDefinition(
              paginationName,
              itemModel,
              _,
              wrappedItemType
            )
          ) =>
        for {
          paginatorPkg <- getPaginatorPkg
          paginatorMethodName = Term.Name(methodName.value + "Paginator")
          publisherType = Type.Name(opName + "Publisher")
          wrappedItem <- wrapSdkValue(itemModel, Term.Name("item"), Term.Select(Term.Name("model"), _))
          awsItemType <- TypeMapping.toJavaType(itemModel)
          modelWrappedItemType = inModel(wrappedItemType)
          streamedPaginator = ServiceMethod(
            interface =
              q"""def $methodName(request: $requestType): zio.stream.ZStream[Any, AwsError, $modelWrappedItemType]""",
            implementation =
              q"""def $methodName(request: $requestType): zio.stream.ZStream[Any, AwsError, $modelWrappedItemType] =
                    asyncJavaPaginatedRequest[$modelPkg.$requestName, $awsItemType, $paginatorPkg.$publisherType](${Lit
                .String(methodName.value)}, api.$paginatorMethodName, _.${Term
                .Name(
                  paginationName.uncapitalize
                )}())(request.buildAwsValue()).map(item => $wrappedItem).provide(r)""",
            accessor =
              q"""def $methodName(request: $requestType): zio.stream.ZStream[$serviceNameT, AwsError, $modelWrappedItemType] =
                    ZStream.accessStream(_.get.$methodName(request))""",
          mockObject =
            q"""object $objectName extends Stream[$requestType, AwsError, $modelWrappedItemType]""",
          mockCompose =
            q"""def $methodName(request: $requestType): ZStream[Any, AwsError, $modelWrappedItemType] = rts.unsafeRun(proxy($objectName, request))"""

          )
        } yield ServiceMethods(streamedPaginator)
      case Some(
            ListPaginationDefinition(memberName, listModel, itemModel, isSimple)
          ) =>
        for {
          wrappedItem <- wrapSdkValue(itemModel, Term.Name("item"), Term.Select(Term.Name("model"), _))
          itemTypeRo <- TypeMapping.toWrappedTypeReadOnly(itemModel).map(inModel)
          awsItemType <- TypeMapping.toJavaType(itemModel)
          responseModel <- context.get(responseName.value)
          property <- propertyName(responseModel, listModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: $requestType): ZStream[Any, AwsError, $itemTypeRo]""",
                implementation =
                  q"""def $methodName(request: $requestType): ZStream[Any, AwsError, $itemTypeRo] =
                    asyncSimplePaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      ${Lit.String(methodName.value)},
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
               """,
                accessor =
                  q"""def $methodName(request: $requestType): ZStream[$serviceNameT, AwsError, $itemTypeRo] =
                    ZStream.accessStream(_.get.$methodName(request))
               """,
                mockObject = q"""object $objectName extends Stream[$requestType, AwsError, $itemTypeRo]""",
                mockCompose = q"""def $methodName(request: $requestType): ZStream[Any, AwsError, $itemTypeRo] = rts.unsafeRun(proxy($objectName, request))"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]]""",
                implementation =
                  q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    asyncPaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      ${Lit.String(methodName.value)},
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map(result =>
                         result
                           .mapResponse(model.$responseNameTerm.wrap)
                           .mapOutput(_.map(item => $wrappedItem))
                           .provide(r))
                      .provide(r)
               """,
                accessor =
                  q"""def $methodName(request: $requestType): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    ZIO.accessM(_.get.$methodName(request))
               """,
                mockObject = q"""object $objectName extends Effect[$requestType, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]]""",
                mockCompose = q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator)

      case Some(
            NestedListPaginationDefinition(
              innerName,
              innerModel,
              resultName,
              resultModel,
              listName,
              listModel,
              itemModel
            )
          ) =>
        for {
          wrappedItem <- wrapSdkValue(itemModel, Term.Name("item"), Term.Select(Term.Name("model"), _))
          itemTypeRo <- TypeMapping.toWrappedTypeReadOnly(itemModel).map(inModel)
          awsItemType <- TypeMapping.toJavaType(itemModel)
          resultTypeRo <- TypeMapping.toWrappedTypeReadOnly(resultModel).map(inModel)
          responseModel <- context.get(responseName.value)
          innerProperty <- propertyName(responseModel, innerModel, innerName)
          innerPropertyNameTerm = Term.Name(innerProperty.javaName)
          listProperty <- propertyName(innerModel, listModel, listName)
          listPropertyNameTerm = Term.Name(listProperty.javaName)
          resultProperty <- propertyName(innerModel, resultModel, resultName)
          resultPropertyNameTerm = Term.Name(resultProperty.javaName)
          resultNameTerm = resultModel.asTerm

          paginator = ServiceMethod(
            interface =
              q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $resultTypeRo, $itemTypeRo]]""",
            implementation =
              q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $resultTypeRo, $itemTypeRo]] =
                    asyncPaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      ${Lit.String(methodName.value)},
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$innerPropertyNameTerm().$listPropertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map(result =>
                         result
                           .mapResponse(r => model.$resultNameTerm.wrap(r.$innerPropertyNameTerm().$resultPropertyNameTerm()))
                           .mapOutput(_.map(item => $wrappedItem))
                           .provide(r))
                      .provide(r)
               """,
            accessor =
              q"""def $methodName(request: $requestType): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $resultTypeRo, $itemTypeRo]] =
                    ZIO.accessM(_.get.$methodName(request))
               """,
            mockObject = q"""object $objectName extends Effect[$requestType, AwsError, StreamingOutputResult[Any, $resultTypeRo, $itemTypeRo]]""",
            mockCompose = q"""def  $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $resultTypeRo, $itemTypeRo]] = proxy($objectName, request)"""
          )
        } yield ServiceMethods(paginator)

      case Some(
            MapPaginationDefinition(
              memberName,
              mapModel,
              keyModel,
              valueModel,
              isSimple
            )
          ) =>
        for {
          wrappedKey <- wrapSdkValue(keyModel, Term.Name("key"), Term.Select(Term.Name("model"), _))
          keyTypeRo <- TypeMapping.toWrappedTypeReadOnly(keyModel).map(inModel)
          awsKeyType <- TypeMapping.toJavaType(keyModel)
          wrappedValue <- wrapSdkValue(valueModel, Term.Name("value"), Term.Select(Term.Name("model"), _))
          valueTypeRo <- TypeMapping.toWrappedTypeReadOnly(valueModel).map(inModel)
          awsValueType <- TypeMapping.toJavaType(valueModel)

          responseModel <- context.get(responseName.value)
          property <- propertyName(responseModel, mapModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: $requestType): ZStream[Any, AwsError, ($keyTypeRo, $valueTypeRo)]""",
                implementation =
                  q"""def $methodName(request: $requestType): ZStream[Any, AwsError, ($keyTypeRo, $valueTypeRo)] =
                    asyncSimplePaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, ($awsKeyType, $awsValueType)](
                      ${Lit.String(methodName.value)},
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map { case (key, value) => $wrappedKey -> $wrappedValue }.provide(r)
               """,
                accessor =
                  q"""def $methodName(request: $requestType): ZStream[$serviceNameT, AwsError, ($keyTypeRo, $valueTypeRo)] =
                    ZStream.accessStream(_.get.$methodName(request))
               """,
                mockObject = q"""object $objectName extends Stream[$requestType, AwsError, ($keyTypeRo, $valueTypeRo)]""",
                mockCompose = q"""def $methodName(request: $requestType): ZStream[Any, AwsError, ($keyTypeRo, $valueTypeRo)] = rts.unsafeRun(proxy($objectName, request))"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]]""",
                implementation =
                  q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]] =
                    asyncPaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, ($awsKeyType, $awsValueType)](
                      ${Lit.String(methodName.value)},
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map { result =>
                        result
                          .mapResponse(model.$responseNameTerm.wrap)
                          .mapOutput(_.map { case (key, value) => $wrappedKey -> $wrappedValue })
                          .provide(r)
                      }.provide(r)
               """,
                accessor =
                  q"""def $methodName(request: $requestType): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]] =
                    ZIO.accessM(_.get.$methodName(request))
               """,
                mockObject = q"""object $objectName extends Effect[$requestType, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]]""",
                mockCompose = q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, ($keyTypeRo, $valueTypeRo)]] = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator)
      case Some(
            StringPaginationDefinition(memberName, stringModel, isSimple)
          ) =>
        for {
          wrappedItem <- wrapSdkValue(stringModel, Term.Name("item"), Term.Select(Term.Name("model"), _))
          itemTypeRo <- TypeMapping.toWrappedTypeReadOnly(stringModel).map(inModel)
          awsItemType <- TypeMapping.toJavaType(stringModel)
          responseModel <- context.get(responseName.value)
          property <- propertyName(responseModel, stringModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: $requestType): ZStream[Any, AwsError, $itemTypeRo]""",
                implementation =
                  q"""def $methodName(request: $requestType): ZStream[Any, AwsError, $itemTypeRo] =
                    asyncSimplePaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      ${Lit.String(methodName.value)},
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk(r.$propertyNameTerm())
                    )(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
               """,
                accessor =
                  q"""def $methodName(request: $requestType): ZStream[$serviceNameT, AwsError, $itemTypeRo] =
                    ZStream.accessStream(_.get.$methodName(request))
               """,
                mockObject = q"""object $objectName extends Stream[$requestType, AwsError, $itemTypeRo]""",
                mockCompose = q"""def $methodName(request: $requestType): ZStream[Any, AwsError, $itemTypeRo] = rts.unsafeRun(proxy($objectName, request))"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]]""",
                implementation =
                  q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    asyncPaginatedRequest[$modelPkg.$requestName, $modelPkg.$responseName, $awsItemType](
                      ${Lit.String(methodName.value)},
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => Option(r.nextToken()),
                      r => Chunk(r.$propertyNameTerm())
                    )(request.buildAwsValue())
                      .map(result =>
                        result
                          .mapResponse(model.$responseNameTerm.wrap)
                          .mapOutput(_.map(item => $wrappedItem))
                          .provide(r))
                      .provide(r)
               """,
                accessor =
                  q"""def $methodName(request: $requestType): ZIO[$serviceNameT, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] =
                    ZIO.accessM(_.get.$methodName(request))
               """,
                mockObject = q"""object $objectName extends Effect[$requestType, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]]""",
                mockCompose = q"""def $methodName(request: $requestType): ZIO[Any, AwsError, StreamingOutputResult[Any, $responseTypeRo, $itemTypeRo]] = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator)
      case None =>
        ZIO.succeed(
          ServiceMethods(
            ServiceMethod(
              interface =
                q"""def $methodName(request: $requestType): IO[AwsError, $responseTypeRo]""",
              implementation =
                q"""def $methodName(request: $requestType): IO[AwsError, $responseTypeRo] =
              asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](${Lit
                  .String(
                    methodName.value
                  )}, api.$methodName)(request.buildAwsValue()).map(model.$responseNameTerm.wrap).provide(r)""",
              accessor =
                q"""def $methodName(request: $requestType): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
              ZIO.accessM(_.get.$methodName(request))""",
              mockObject = q"""object $objectName extends Effect[$requestType, AwsError, $responseTypeRo]""",
              mockCompose = q"""def $methodName(request: $requestType): IO[AwsError, $responseTypeRo] = proxy($objectName, request)"""
            )
          )
        )
    }
  }

  private def generateRequestToUnit(
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestType: Type.Select,
      responseName: Type.Name,
      modelPkg: Term.Ref
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: $requestType): IO[AwsError, scala.Unit]""",
          implementation =
            q"""def $methodName(request: $requestType): IO[AwsError, scala.Unit] =
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](${Lit
              .String(
                methodName.value
              )}, api.$methodName)(request.buildAwsValue()).unit.provide(r)""",
          accessor =
            q"""def $methodName(request: $requestType): ZIO[$serviceNameT, AwsError, scala.Unit] =
                ZIO.accessM(_.get.$methodName(request))""",
          mockObject = q"""object $objectName extends Effect[$requestType, AwsError, scala.Unit]""",
          mockCompose = q"""def $methodName(request: $requestType): IO[AwsError, scala.Unit] = proxy($objectName, request)"""
        )
      )
    )
  }

  private def generateUnitToResponse(
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestNameTerm: Term.Name,
      responseName: Type.Name,
      responseNameTerm: Term.Name,
      responseTypeRo: Type.Select,
      modelPkg: Term.Ref
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface = q"""def $methodName(): IO[AwsError, $responseTypeRo]""",
          implementation =
            q"""def $methodName(): IO[AwsError, $responseTypeRo] =
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](${Lit
              .String(
                methodName.value
              )}, api.$methodName)($modelPkg.$requestNameTerm.builder().build()).map(model.$responseNameTerm.wrap).provide(r)""",
          accessor =
            q"""def $methodName(): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName())""",
          mockObject = q"""object $objectName extends Effect[Unit, AwsError, $responseTypeRo]""",
          mockCompose = q"""def $methodName(): IO[AwsError, $responseTypeRo] = proxy($objectName)"""
        )
      )
    )
  }

  private def generateUnitToUnit(
      serviceNameT: Type.Name,
      methodName: Term.Name,
      requestName: Type.Name,
      requestNameTerm: Term.Name,
      responseName: Type.Name,
      modelPkg: Term.Ref
  ) = {
    val objectName = Term.Name(methodName.value.capitalize)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface = q"""def $methodName(): IO[AwsError, scala.Unit]""",
          implementation = q"""def $methodName(): IO[AwsError, scala.Unit] =
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](${Lit
            .String(
              methodName.value
            )}, api.$methodName)($modelPkg.$requestNameTerm.builder().build()).unit.provide(r)""",
          accessor =
            q"""def $methodName(): ZIO[$serviceNameT, AwsError, scala.Unit] =
                ZIO.accessM(_.get.$methodName())""",
          mockObject = q"""object $objectName extends Effect[scala.Unit, AwsError, scala.Unit]""",
          mockCompose = q"""def $methodName(): IO[AwsError, scala.Unit] = proxy($objectName)"""
        )
      )
    )
  }

  private def generateServiceModuleCode()
      : ZIO[GeneratorContext, GeneratorFailure, String] =
    for {
      id <- getService
      namingStrategy <- getNamingStrategy
      paginatorPackage <- getPaginatorPkg
      pkgName = Term.Name(id.moduleName)
      serviceName = Term.Name(namingStrategy.getServiceName)
      serviceNameMock = Term.Name(namingStrategy.getServiceName + "Mock")
      serviceImplName = Term.Name(serviceName.value + "Impl")
      serviceImplT = Type.Name(serviceImplName.value)
      serviceNameT = Type.Name(serviceName.value)
      serviceTrait = Type.Select(serviceName, Type.Name("Service"))
      clientInterface = Type.Name(serviceName.value + "AsyncClient")
      clientInterfaceSingleton = Term.Name(serviceName.value + "AsyncClient")
      clientInterfaceBuilder =
        Type.Name(serviceName.value + "AsyncClientBuilder")

      serviceMethods <- generateServiceMethods()
      ops <- awsModel.getOperations
      javaSdkPaginations <- ZIO.foreach(ops) { case (opName, op) =>
        OperationCollector.get(opName, op).map {
          case RequestResponse(
                Some(JavaSdkPaginationDefinition(_, _, _, _))
              ) =>
            true
          case _ => false
        }
      }
      usesJavaSdkPaginators = javaSdkPaginations.exists(identity)

      serviceMethodIfaces = serviceMethods.flatMap(_.methods.map(_.interface))
      serviceMethodImpls =
        serviceMethods.flatMap(_.methods.map(_.implementation))
      serviceAccessors = serviceMethods.flatMap(_.methods.map(_.accessor))
      serviceMethodMockObjects = serviceMethods.flatMap(_.methods.map(_.mockObject))
      serviceMethodMockComposeMethods = serviceMethods.flatMap(_.methods.map(_.mockCompose))

      supportsHttp2 = id.name == "kinesis" || id.name == "transcribestreaming"
      supportsHttp2Lit = Lit.Boolean(supportsHttp2)

      imports = List(
        Some(q"""import io.github.vigoo.zioaws.core._"""),
        Some(q"""import io.github.vigoo.zioaws.core.aspects._"""),
        Some(q"""import io.github.vigoo.zioaws.core.config.AwsConfig"""),
        Some(
          q"""import io.github.vigoo.zioaws.core.httpclient.ServiceHttpCapabilities"""
        ),
        Some(q"""import software.amazon.awssdk.core.client.config.{ ClientAsyncConfiguration, SdkAdvancedAsyncClientOption } """),
        if (usesJavaSdkPaginators)
          Some(
            Import(List(Importer(paginatorPackage, List(Importee.Wildcard()))))
          )
        else None,
        Some(id.subModuleName match {
          case Some(submodule) if submodule != id.name =>
            Import(
              List(
                Importer(
                  q"software.amazon.awssdk.services.${Term.Name(id.name)}.${Term
                    .Name(submodule)}",
                  List(
                    Importee.Name(Name(clientInterface.value)),
                    Importee.Name(Name(clientInterfaceBuilder.value))
                  )
                )
              )
            )
          case _ =>
            Import(
              List(
                Importer(
                  q"software.amazon.awssdk.services.$pkgName",
                  List(
                    Importee.Name(Name(clientInterface.value)),
                    Importee.Name(Name(clientInterfaceBuilder.value))
                  )
                )
              )
            )
        }),
        Some(q"""import zio.{Chunk, Has, IO, URLayer, ZIO, ZLayer, ZManaged}"""),
        Some(q"""import zio.stream.ZStream"""),
        Some(q"""import org.reactivestreams.Publisher"""),
        Some(q"""import scala.jdk.CollectionConverters._""")
      ).flatten

      mockCompose = q"""
        val compose: URLayer[Has[zio.test.mock.Proxy], $serviceNameT] = 
          ZLayer.fromServiceM { proxy =>
            withRuntime.map { rts => 
              new ${Init(serviceTrait, Name.Anonymous(), List.empty)} {
                val api: $clientInterface = null
                def withAspect[R1](newAspect: AwsCallAspect[R1], r: R1): $serviceTrait = this

                ..$serviceMethodMockComposeMethods
              }
            }
          }
        """

      module = q"""
        package object $pkgName {
          type $serviceNameT = Has[$serviceTrait]

          object $serviceName {
            trait Service extends AspectSupport[Service] {
              val api: $clientInterface

              ..$serviceMethodIfaces
            }

            object $serviceNameMock extends zio.test.mock.Mock[$serviceNameT] {
              ..$serviceMethodMockObjects

              $mockCompose
            }
          }

          val live: ZLayer[AwsConfig, Throwable, $serviceNameT] = customized(identity)

          def customized(customization: $clientInterfaceBuilder => $clientInterfaceBuilder): ZLayer[AwsConfig, Throwable, $serviceNameT] =
            managed(customization).toLayer

          def managed(customization: $clientInterfaceBuilder => $clientInterfaceBuilder): ZManaged[AwsConfig, Throwable, $serviceTrait] =
            for {
              awsConfig <- ZManaged.service[AwsConfig.Service]
              executor <- ZIO.executor.toManaged_
              builder = $clientInterfaceSingleton.builder()
                  .asyncConfiguration(
                    ClientAsyncConfiguration
                      .builder()
                      .advancedOption(
                        SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR,
                        executor.asJava
                      )
                      .build()
                  )
              b0 <- awsConfig.configure[$clientInterface, $clientInterfaceBuilder](builder).toManaged_
              b1 <- awsConfig.configureHttpClient[$clientInterface, $clientInterfaceBuilder](b0, ServiceHttpCapabilities(supportsHttp2 = $supportsHttp2Lit)).toManaged_
              client <- ZIO(customization(b1).build()).toManaged_
            } yield new $serviceImplT(client, AwsCallAspect.identity, ().asInstanceOf[Any])

          private class $serviceImplT[R](override val api: $clientInterface, override val aspect: AwsCallAspect[R], r: R)
            extends ${Init(serviceTrait, Name.Anonymous(), List.empty)} with AwsServiceBase[R, $serviceImplT] {
              override val serviceName: String = ${Lit
        .String(serviceName.value)}
              override def withAspect[R1](newAspect: AwsCallAspect[R1], r: R1): $serviceImplT[R1] =
                new $serviceImplT(api, newAspect, r)

              ..$serviceMethodImpls
            }

          ..$serviceAccessors
        }
      """
      pkg = q"""package io.github.vigoo.zioaws {
              ..$imports
              $module
            }
          """
    } yield prettyPrint(pkg)

  protected def generateServiceModule()
      : ZIO[GeneratorContext with Blocking, GeneratorFailure, File] =
    for {
      code <- generateServiceModuleCode()
      id <- getService
      moduleName = id.moduleName
      targetRoot = config.targetRoot
      packageParent = targetRoot / "io/github/vigoo/zioaws"
      packageRoot = packageParent / moduleName
      moduleFile = packageRoot / "package.scala"
      _ <-
        Files.createDirectories(packageRoot).mapError(FailedToCreateDirectories)
      _ <- writeIfDifferent(moduleFile, code)
    } yield moduleFile.toFile

}
