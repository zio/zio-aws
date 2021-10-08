package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.metagen.core._
import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.generator.syntax._
import io.github.vigoo.zioaws.codegen.generator.OperationMethodType._
import software.amazon.awssdk.codegen.model.service.Operation
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.{Has, UIO, ZIO}

import scala.jdk.CollectionConverters._
import scala.meta._

trait ServiceInterfaceGenerator {
  this: HasConfig with GeneratorBase =>

  private def opMethodName(opName: String): Term.Name =
    Term.Name(opName.toCamelCase)

  private def serviceType
      : ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] =
    for {
      namingStrategy <- getNamingStrategy
      pkg <- getPkg
      name <- ZIO.effect(namingStrategy.getServiceName).mapError(UnknownError)
    } yield ScalaType(pkg, name)

  private def opRequestType(
      opName: String
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] =
    for {
      namingStrategy <- getNamingStrategy
      pkg <- getPkg
      name <- ZIO
        .effect(namingStrategy.getRequestClassName(opName))
        .mapError(UnknownError)
    } yield ScalaType(pkg / "model", name)

  private def opResponseType(
      opName: String
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] =
    for {
      namingStrategy <- getNamingStrategy
      pkg <- getPkg
      name <- ZIO
        .effect(namingStrategy.getResponseClassName(opName))
        .mapError(UnknownError)
    } yield ScalaType(pkg / "model", name)

  private def findEventStreamShape(
      outputShape: String
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, NamedShape] =
    getModels.flatMap { models =>
      ModelCollector.tryFindEventStreamShape(models, outputShape) match {
        case Some(value) => ZIO.succeed(value)
        case None =>
          getServiceName.flatMap(serviceName =>
            ZIO.fail(CannotFindEventStreamInShape(serviceName, outputShape))
          )
      }
    }

  private def generateServiceMethods()
      : ZIO[AwsGeneratorContext, AwsGeneratorFailure, List[ServiceMethods]] = {
    getModels.flatMap { models =>
      val ops = OperationCollector.getFilteredOperations(models)
      ZIO.foreach(ops.toList) { case (opName, op) =>
        for {
          serviceNameT <- serviceType
          methodName = opMethodName(opName)
          requestType <- opRequestType(opName)
          responseType <- opResponseType(opName)
          responseTypeRo = responseType / "ReadOnly"
          modelPkg <- getModelPkg
          javaRequestType = ScalaType(modelPkg, requestType.name)
          javaResponseType = ScalaType(modelPkg, responseType.name)
          operation <- OperationCollector.get(opName, op)
          result <- operation match {
            case UnitToUnit =>
              generateUnitToUnit(
                serviceNameT,
                methodName,
                javaRequestType,
                javaResponseType
              )
            case UnitToResponse =>
              generateUnitToResponse(
                serviceNameT,
                methodName,
                responseType,
                responseTypeRo,
                javaRequestType,
                javaResponseType
              )
            case RequestToUnit =>
              generateRequestToUnit(
                serviceNameT,
                methodName,
                requestType,
                javaRequestType,
                javaResponseType
              )
            case RequestResponse(pagination) =>
              generateRequestToResponse(
                opName,
                serviceNameT,
                methodName,
                requestType,
                responseType,
                responseTypeRo,
                pagination,
                javaRequestType,
                javaResponseType
              )
            case StreamedInput =>
              generateStreamedInput(
                 serviceNameT,
                 methodName,
                 requestType,
                 responseType,
                 responseTypeRo,
                javaRequestType,
                javaResponseType
              )
            case StreamedInputToUnit =>
              generateStreamedInputToUnit(
                 serviceNameT,
                 methodName,
                 requestType,
                 responseType,
                javaRequestType,
                javaResponseType
              )
            case StreamedOutput =>
              generateStreamedOutput(
                 serviceNameT,
                 methodName,
                 requestType,
                 responseType,
                 responseTypeRo,
                javaRequestType,
                javaResponseType
              )
            case StreamedInputOutput =>
              generateStreamedInputOutput(
                 serviceNameT,
                 methodName,
                 requestType,
                 responseType,
                 responseTypeRo,
                javaRequestType,
                javaResponseType
              )
            case EventStreamInput =>
              generateEventStreamInput(
                modelPkg,
                op,
                serviceNameT,
                methodName,
                requestType,
                responseType,
                responseTypeRo,
                javaRequestType,
                javaResponseType
              )
            case EventStreamOutput =>
              generateEventStreamOutput(
                modelPkg,
                opName,
                op,
                serviceNameT,
                methodName,
                requestType,
                responseType,
                javaRequestType,
                javaResponseType
              )
            case EventStreamInputOutput =>
              generateEventStreamInputOutput(
                modelPkg,
                opName,
                op,
                serviceNameT,
                methodName,
                requestType,
                responseType,
                javaRequestType,
                javaResponseType
              )
          }
        } yield result
      }
    }
  }

  private def generateEventStreamInputOutput(
      modelPkg: Package,
      opName: String,
      op: Operation,
      serviceType: ScalaType,
      methodName: Term.Name,
      requestType: ScalaType,
      responseType: ScalaType,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ServiceMethods] = {
    for {
      inputEventStream <- findEventStreamShape(op.getInput.getShape)
      awsInEventStreamT = ScalaType(modelPkg, inputEventStream.name)
      inEventShapeName = inputEventStream.shape.getMembers.asScala.keys.head
      inEventT <- get(inEventShapeName).map(_.generatedType)

      outputEventStream <- findEventStreamShape(op.getOutput.getShape)
      outEventShapeName = outputEventStream.shape.getMembers.asScala.keys.head
      outEventModel <- get(outEventShapeName)
      awsOutEventT <- TypeMapping.toJavaType(outEventModel)

      awsOutEventStreamT = ScalaType(modelPkg, outputEventStream.name)
      outEventRoT = outEventModel.generatedType / "ReadOnly"
      responseHandlerName = opName + "ResponseHandler"
      responseHandlerT = ScalaType(modelPkg, responseHandlerName)

      wrappedItem <- wrapSdkValue(
        outEventModel,
        Term.Name("item")
      )
      opNameLit = Lit.String(opName)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, inEventT).typ}): ${Types.zioStreamAwsError(ScalaType.any, outEventRoT).typ}""",
        implementation =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, inEventT).typ}): ${Types.zioStreamAwsError(ScalaType.any, outEventRoT).typ} =
                asyncRequestEventInputOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsInEventStreamT.typ}, ${responseHandlerT.typ}, ${awsOutEventStreamT.typ}, ${awsOutEventT.typ}](
                    $opNameLit,
                    (request: ${requestType.typ}, input: ${Types.rsPublisher(awsInEventStreamT).typ}, handler: ${responseHandlerT.typ}) => api.$methodName(request, input, handler),
                    (impl: ${Types.eventStreamResponseHandler(responseType, awsOutEventStreamT).typ}) =>
                      new ${responseHandlerT.init} {
                        override def responseReceived(response: ${responseType.typ}): ${ScalaType.unit.typ} = impl.responseReceived(response)
                        override def onEventStream(publisher: ${Types.sdkPublisher(awsOutEventStreamT).typ}): ${ScalaType.unit.typ} = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: ${Types.throwable.typ}): ${ScalaType.unit.typ} = impl.exceptionOccurred(throwable)
                        override def complete(): ${ScalaType.unit.typ} = impl.complete()
                      })(request.buildAwsValue(), input.map(_.buildAwsValue())).map(item => $wrappedItem).provide(r)
               """,
        accessor =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, inEventT).typ}): ${Types.zioStreamAwsError(serviceType, outEventRoT).typ} =
                ${Types.zioStream_.term}.accessStream(_.get.$methodName(request, input))""",
        mockObject =
          q"""object $objectName extends Stream[${ScalaType.pair(requestType, Types.zioStreamAwsError(ScalaType.any, inEventT)).typ}, ${Types.awsError.typ}, ${outEventRoT.typ}]""",
        mockCompose =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, inEventT).typ}): ${Types.zioStreamAwsError(ScalaType.any, outEventRoT).typ} = rts.unsafeRun(proxy($objectName, request, input))"""
      )
    )
  }

  private def generateEventStreamOutput(
      modelPkg: Package,
      opName: String,
      op: Operation,
      serviceType: ScalaType,
      methodName: Term.Name,
      requestType: ScalaType,
      responseType: ScalaType,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ServiceMethods] = {
    for {
      eventStream <- findEventStreamShape(op.getOutput.getShape)
      awsEventStreamT = ScalaType(modelPkg, eventStream.name)
      rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
      eventItemModel <- get(rawEventItemName)
      eventRoT = eventItemModel.generatedType / "ReadOnly"
        
      awsEventT <- TypeMapping.toJavaType(eventItemModel)
      responseHandlerName = opName + "ResponseHandler"
      responseHandlerT = ScalaType(modelPkg, responseHandlerName)

      wrappedItem <- wrapSdkValue(
        eventItemModel,
        Term.Name("item")
      )
      opNameLit = Lit.String(opName)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, eventRoT).typ}""",
        implementation =
          q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, eventRoT).typ} =
                  asyncRequestEventOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}, ${responseHandlerT.typ}, ${awsEventStreamT.typ}, ${awsEventT.typ}](
                    $opNameLit,
                    (request: ${javaRequestType.typ}, handler: ${responseHandlerT.typ}) => api.$methodName(request, handler),
                    (impl: ${Types.eventStreamResponseHandler(javaResponseType, awsEventStreamT).typ}) =>
                      new ${responseHandlerT.init} {
                        override def responseReceived(response: ${javaResponseType.typ}): ${ScalaType.unit.typ} = impl.responseReceived(response)
                        override def onEventStream(publisher: ${Types.sdkPublisher(awsEventStreamT).typ}): ${ScalaType.unit.typ} = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: ${Types.throwable.typ}): ${ScalaType.unit.typ} = impl.exceptionOccurred(throwable)
                        override def complete(): ${ScalaType.unit.typ} = impl.complete()
                      })(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
              """,
        accessor =
          q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(serviceType, eventRoT).typ} =
                ${Types.zioStream_.term}.accessStream(_.get.$methodName(request))""",
        mockObject =
          q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${eventRoT.typ}]""",
        mockCompose =
          q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, eventRoT).typ} = rts.unsafeRun(proxy($objectName, request))"""
      )
    )
  }

  private def generateEventStreamInput(
      modelPkg: Package,
      op: Operation,    
      serviceType: ScalaType,
      methodName: Term.Name,
      requestType: ScalaType,
      responseType: ScalaType,
      responseTypeRo: ScalaType,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ServiceMethods] = {
    for {
      eventStream <- findEventStreamShape(op.getInput.getShape)
      awsInEventStreamT = modelPkg / eventStream.name
      rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
      eventT <- get(rawEventItemName).map(_.generatedType)
      opNameLit = Lit.String(methodName.value)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, eventT).typ}): ${Types.ioAwsError(responseTypeRo).typ}""",
        implementation =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, eventT).typ}): ${Types.ioAwsError(responseTypeRo).typ} =
                asyncRequestEventInputStream[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsInEventStreamT.typ}]($opNameLit, api.$methodName)(request.buildAwsValue(), input.map(_.buildAwsValue())).provide(r)""",
        accessor =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, eventT).typ}): ${Types.zioAwsError(serviceType, responseTypeRo).typ} =
                ${Types.zio_.term}.accessM(_.get.$methodName(request, input))""",
        mockObject =
          q"""object $objectName extends Effect[${ScalaType.pair(requestType, Types.zioStreamAwsError(ScalaType.any, eventT)).typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
        mockCompose =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types.zioStreamAwsError(ScalaType.any, eventT).typ}): ${Types.ioAwsError(responseTypeRo).typ} = proxy($objectName, request, input)"""
      )
    )
  }

  private def generateStreamedInputOutput(
    serviceType: ScalaType,
    methodName: Term.Name,
    requestType: ScalaType,
    responseType: ScalaType,
    responseTypeRo: ScalaType,
    javaRequestType: ScalaType,
    javaResponseType: ScalaType
  ): UIO[ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ}""",
          implementation =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ} =
                asyncRequestInputOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}](
                  $methodNameLit,
                  api.$methodName[zio.Task[StreamingOutputResult[R, ${responseType.typ}, Byte]]]
                )(request.buildAwsValue(), body)
                  .map(_.mapResponse(${responseType.term}.wrap).provide(r))
                  .provide(r)
          """, // TODO: needs type param support to ScalaType
          accessor =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.zioAwsError(serviceType, Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ} =
                ${Types.zio_.term}.accessM(_.get.$methodName(request, body))""",
          mockObject =
            q"""object $objectName extends Effect[${ScalaType.pair(requestType, Types.zioStreamAwsError(ScalaType.any, ScalaType.byte)).typ}, ${Types.awsError.typ}, ${Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte).typ}]""",
          mockCompose =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ} = proxy($objectName, request, body)"""
        )
      )
    )
  }

  private def generateStreamedOutput(
      serviceType: ScalaType,
      methodName: Term.Name,
      requestType: ScalaType,
      responseType: ScalaType,
      responseTypeRo: ScalaType,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): UIO[ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ}""",
          implementation =
            q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ} =
                asyncRequestOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}](
                  $methodNameLit,
                  api.$methodName[zio.Task[StreamingOutputResult[R, ${javaResponseType.typ}, Byte]]]
                )(request.buildAwsValue())
                  .map(_.mapResponse(${responseType.term}.wrap).provide(r))
                  .provide(r)""",  // TODO: needs type param support to ScalaType
          accessor =
            q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(serviceType, Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ} =
                ${Types.zio_.term}.accessM(_.get.$methodName(request))""",
          mockObject =
            q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte).typ}]""",
          mockCompose =
            q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.byte)).typ} = proxy($objectName, request)"""
        )
      )
    )
  }

  private def generateStreamedInput(
      serviceType: ScalaType,
      methodName: Term.Name,
      requestType: ScalaType,
      responseType: ScalaType,
      responseTypeRo: ScalaType,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): UIO[ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(responseTypeRo).typ}""",
          implementation =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(responseTypeRo).typ} =
              asyncRequestInputStream[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(request.buildAwsValue(), body).map(${responseType.term}.wrap).provide(r)""",
          accessor =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.zioAwsError(serviceType, responseTypeRo).typ} =
              ${Types.zio_.term}.accessM(_.get.$methodName(request, body))""",
          mockObject =
            q"""object $objectName extends Effect[${ScalaType.pair(requestType, Types.zioStreamAwsError(ScalaType.any, ScalaType.byte)).typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
          mockCompose =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(responseTypeRo).typ} = proxy($objectName, request, body)"""
        )
      )
    )
  }

  private def generateStreamedInputToUnit(
    serviceType: ScalaType,
    methodName: Term.Name,
    requestType: ScalaType,
    responseType: ScalaType,
    javaRequestType: ScalaType,
    javaResponseType: ScalaType
  ): UIO[ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(ScalaType.unit).typ}""",
                      implementation = q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(ScalaType.unit).typ} =
                  asyncRequestInputStream[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(request.buildAwsValue(), body).unit.provide(r)""",
          accessor =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.zioAwsError(serviceType, ScalaType.unit).typ} =
                ${Types.zio_.term}.accessM(_.get.$methodName(request, body))""",
          mockObject =
            q"""object $objectName extends Effect[${ScalaType.pair(requestType, Types.zioStreamAwsError(ScalaType.any, ScalaType.byte)).typ}, ${Types.awsError.typ}, ${ScalaType.unit.typ}]""",
          mockCompose =
            q"""def $methodName(request: ${requestType.typ}, body: ${Types.zioStreamAwsError(ScalaType.any, ScalaType.byte).typ}): ${Types.ioAwsError(ScalaType.unit).typ} = proxy($objectName, request, body)"""
        )
      )
    )
  }

  private def generateRequestToResponse(
      opName: String,
      serviceType: ScalaType,
      methodName: Term.Name,
      requestType: ScalaType,
      responseType: ScalaType,
      responseTypeRo: ScalaType,      
      pagination: Option[PaginationDefinition],
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
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
          publisher = ScalaType(paginatorPkg, opName + "Publisher")
          wrappedItem <- wrapSdkValue(
            itemModel,
            Term.Name("item")
          )
          awsItemType <- TypeMapping.toJavaType(itemModel)
          streamedPaginator = ServiceMethod(
            interface =
              q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, wrappedItemType).typ}""",
            implementation =
              q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, wrappedItemType).typ} =
                    asyncJavaPaginatedRequest[${javaRequestType.typ}, ${awsItemType.typ}, ${publisher.typ}]($methodNameLit, api.$paginatorMethodName, _.${Term
                .Name(
                  paginationName.uncapitalize
                )}())(request.buildAwsValue()).map(item => $wrappedItem).provide(r)""",
            accessor =
              q"""def $methodName(request: ${requestType.typ}): ${Types.zioStream(serviceType, Types.awsError, wrappedItemType).typ} =
                    ${Types.zioStream_.term}.accessStream(_.get.$methodName(request))""",
            mockObject =
              q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${wrappedItemType.typ}]""",
            mockCompose =
              q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, wrappedItemType).typ} = rts.unsafeRun(proxy($objectName, request))"""
          )
        } yield ServiceMethods(streamedPaginator)
      case Some(
            ListPaginationDefinition(memberName, listModel, itemModel, isSimple)
          ) =>
        for {
          wrappedItem <- wrapSdkValue(
            itemModel,
            Term.Name("item")
          )
          itemTypeRo <- TypeMapping.toWrappedTypeReadOnly(itemModel)
          awsItemType <- TypeMapping.toJavaType(itemModel)
          responseModel <- context.get(responseType.name)
          property <- propertyName(responseModel, listModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, itemTypeRo).typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, itemTypeRo).typ} =
                    asyncSimplePaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsItemType.typ}](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStream(serviceType, Types.awsError, itemTypeRo).typ} =
                    ${Types.zioStream_.term}.accessStream(_.get.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${itemTypeRo.typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, itemTypeRo).typ} = rts.unsafeRun(proxy($objectName, request))"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(ScalaType.any, Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(ScalaType.any, Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ} =
                    asyncPaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsItemType.typ}](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map(result =>
                         result
                           .mapResponse(${responseType.term}.wrap)
                           .mapOutput(_.map(item => $wrappedItem))
                           .provide(r))
                      .provide(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(serviceType, Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ} =
                    ${Types.zio_.term}.accessM(_.get.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo).typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(ScalaType.any, Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ} = proxy($objectName, request)"""
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
          wrappedItem <- wrapSdkValue(
            itemModel,
            Term.Name("item")
          )
          itemTypeRo <- TypeMapping
            .toWrappedTypeReadOnly(itemModel)
          awsItemType <- TypeMapping.toJavaType(itemModel)
          resultTypeRo <- TypeMapping.toWrappedTypeReadOnly(resultModel)
          responseModel <- context.get(responseType.name)
          innerProperty <- propertyName(responseModel, innerModel, innerName)
          innerPropertyNameTerm = Term.Name(innerProperty.javaName)
          listProperty <- propertyName(innerModel, listModel, listName)
          listPropertyNameTerm = Term.Name(listProperty.javaName)
          resultProperty <- propertyName(innerModel, resultModel, resultName)
          resultPropertyNameTerm = Term.Name(resultProperty.javaName)

          paginator = ServiceMethod(
            interface =
              q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, resultTypeRo, itemTypeRo)).typ}""",
            implementation =
              q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, resultTypeRo, itemTypeRo)).typ} =
                    asyncPaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsItemType.typ}](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}.fromIterable(r.$innerPropertyNameTerm().$listPropertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map(result =>
                         result
                           .mapResponse(r => ${resultModel.generatedType.term}.wrap(r.$innerPropertyNameTerm().$resultPropertyNameTerm()))
                           .mapOutput(_.map(item => $wrappedItem))
                           .provide(r))
                      .provide(r)
               """,
            accessor =
              q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(serviceType, Types.streamingOutputResult(ScalaType.any, resultTypeRo, itemTypeRo)).typ} =
                    ${Types.zio_.term}.accessM(_.get.$methodName(request))
               """,
            mockObject =
              q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types.streamingOutputResult(ScalaType.any, resultTypeRo, itemTypeRo).typ}]""",
            mockCompose =
              q"""def  $methodName(request: ${requestType.typ}): ${Types.zioAwsError(ScalaType.any, Types.streamingOutputResult(ScalaType.any, resultTypeRo, itemTypeRo)).typ} = proxy($objectName, request)"""
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
          wrappedKey <- wrapSdkValue(
            keyModel,
            Term.Name("key")
          )
          keyTypeRo <- TypeMapping.toWrappedTypeReadOnly(keyModel)
          awsKeyType <- TypeMapping.toJavaType(keyModel)
          wrappedValue <- wrapSdkValue(
            valueModel,
            Term.Name("value")
          )
          valueTypeRo <- TypeMapping
            .toWrappedTypeReadOnly(valueModel)
          awsValueType <- TypeMapping.toJavaType(valueModel)

          responseModel <- context.get(responseType.name)
          property <- propertyName(responseModel, mapModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, ScalaType.pair(keyTypeRo, valueTypeRo)).typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, ScalaType.pair(keyTypeRo, valueTypeRo)).typ} =
                    asyncSimplePaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, (${awsKeyType.typ}, ${awsValueType.typ})](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map { case (key, value) => $wrappedKey -> $wrappedValue }.provide(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(serviceType, ScalaType.pair(keyTypeRo, valueTypeRo)).typ} =
                    ${Types.zioStream_.term}.accessStream(_.get.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${ScalaType.pair(keyTypeRo, valueTypeRo).typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, ScalaType.pair(keyTypeRo, valueTypeRo)).typ} = rts.unsafeRun(proxy($objectName, request))"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.pair(keyTypeRo, valueTypeRo))).typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.pair(keyTypeRo, valueTypeRo))).typ} =
                    asyncPaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, (${awsKeyType.typ}, ${awsValueType.typ})](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue())
                      .map { result =>
                        result
                          .mapResponse(${responseType.term}.wrap)
                          .mapOutput(_.map { case (key, value) => $wrappedKey -> $wrappedValue })
                          .provide(r)
                      }.provide(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(serviceType, Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.pair(keyTypeRo, valueTypeRo))).typ} =
                    ${Types.zio_.term}.accessM(_.get.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.pair(keyTypeRo, valueTypeRo)).typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, ScalaType.pair(keyTypeRo, valueTypeRo))).typ} = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator)
      case Some(
            StringPaginationDefinition(memberName, stringModel, isSimple)
          ) =>
        for {
          wrappedItem <- wrapSdkValue(
            stringModel,
            Term.Name("item")
          )
          itemTypeRo <- TypeMapping
            .toWrappedTypeReadOnly(stringModel)
          awsItemType <- TypeMapping.toJavaType(stringModel)
          responseModel <- context.get(responseType.name)
          property <- propertyName(responseModel, stringModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioStreamAwsError(itemTypeRo).typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioStreamAwsError(itemTypeRo).typ} =
                    asyncSimplePaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsItemType.typ}](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}(r.$propertyNameTerm())
                    )(request.buildAwsValue()).map(item => $wrappedItem).provide(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(serviceType, itemTypeRo).typ} =
                    ${Types.zioStream_.term}.accessStream(_.get.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${itemTypeRo.typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioStreamAwsError(ScalaType.any, itemTypeRo).typ} = rts.unsafeRun(proxy($objectName, request))"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ} =
                    asyncPaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsItemType.typ}](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}(r.$propertyNameTerm())
                    )(request.buildAwsValue())
                      .map(result =>
                        result
                          .mapResponse(${responseType.term}.wrap)
                          .mapOutput(_.map(item => $wrappedItem))
                          .provide(r))
                      .provide(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(serviceType, Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ} =
                    ${Types.zio_.term}.accessM(_.get.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo).typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(Types.streamingOutputResult(ScalaType.any, responseTypeRo, itemTypeRo)).typ} = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator)
      case None =>
        ZIO.succeed(
          ServiceMethods(
            ServiceMethod(
              interface =
                q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(responseTypeRo).typ}""",
              implementation =
                q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(responseTypeRo).typ} =
                      asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(request.buildAwsValue()).map(${responseType.term}.wrap).provide(r)""",
              accessor =
                q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(serviceType, responseTypeRo).typ} =
                ${Types.zio_.term}.accessM(_.get.$methodName(request))""",
              mockObject =
                q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
              mockCompose =
                q"""def $methodName(request: ${requestType.typ}):  ${Types.ioAwsError(responseTypeRo).typ} = proxy($objectName, request)"""
            )
          )
        )
    }
  }

  private def generateRequestToUnit(
    serviceType: ScalaType,
    methodName: Term.Name,
    requestType: ScalaType,
    javaRequestType: ScalaType,
    javaResponseType: ScalaType
  ): UIO[ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface =
            q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(ScalaType.unit).typ}""",
          implementation =
            q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(ScalaType.unit).typ} =
                asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(request.buildAwsValue()).unit.provide(r)""",
          accessor =
            q"""def $methodName(request: ${requestType.typ}): ${Types.zioAwsError(serviceType, ScalaType.unit).typ} =
                ${Types.zio_.term}.accessM(_.get.$methodName(request))""",
          mockObject =
            q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${ScalaType.unit.typ}]""",
          mockCompose =
            q"""def $methodName(request: ${requestType.typ}): ${Types.ioAwsError(ScalaType.unit).typ} = proxy($objectName, request)"""
        )
      )
    )
  }

  private def generateUnitToResponse(
      serviceType: ScalaType,
      methodName: Term.Name,
      responseType: ScalaType,
      responseTypeRo: ScalaType,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): UIO[ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface = q"""def $methodName(): ${Types.ioAwsError(responseTypeRo).typ}""",
          implementation =
            q"""def $methodName(): ${Types.ioAwsError(responseTypeRo).typ} =
                  asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(${javaRequestType.term}.builder().build()).map(${responseType.term}.wrap).provide(r)""",
          accessor =
            q"""def $methodName(): ${Types.zioAwsError(serviceType, responseTypeRo).typ} =
                  ${Types.zio_.term}.accessM(_.get.$methodName())""",
          mockObject =
            q"""object $objectName extends Effect[${ScalaType.unit.typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
          mockCompose =
            q"""def $methodName(): ${Types.ioAwsError(responseTypeRo).typ} = proxy($objectName)"""
        )
      )
    )
  }

  private def generateUnitToUnit(
      serviceType: ScalaType,
      methodName: Term.Name,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType
  ): UIO[ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    ZIO.succeed(
      ServiceMethods(
        ServiceMethod(
          interface = q"""def $methodName(): ${Types.ioAwsError(ScalaType.unit).typ}""",
          implementation = q"""def $methodName(): ${Types.ioAwsError(ScalaType.unit).typ} =
                asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(${javaRequestType.term}.builder().build()).unit.provide(r)""",
          accessor =
            q"""def $methodName(): ${Types.zioAwsError(serviceType, ScalaType.unit).typ} =
                ${Types.zio_.term}.accessM(_.get.$methodName())""",
          mockObject =
            q"""object $objectName extends Effect[${ScalaType.unit.typ}, ${Types.awsError.typ}, ${ScalaType.unit.typ}]""",
          mockCompose =
            q"""def $methodName(): ${Types.ioAwsError(ScalaType.unit).typ} = proxy($objectName)"""
        )
      )
    )
  }

  private def generateServiceModuleCode(): ZIO[Has[
    Generator
  ] with Blocking with AwsGeneratorContext, GeneratorFailure[
    AwsGeneratorFailure
  ], Path] =
    for {
      id <- getService
      namingStrategy <- getNamingStrategy
      sdkPackage <- getModelPkg.map(_.parent) // TODO
      pkg <- getPkg
      serviceName = Term.Name(namingStrategy.getServiceName)
      serviceNameMock = Term.Name(namingStrategy.getServiceName + "Mock")
      serviceImplName = Term.Name(serviceName.value + "Impl")
      serviceImplT = Type.Name(serviceImplName.value)
      serviceNameT = Type.Name(serviceName.value)
      serviceTrait = Type.Select(serviceName, Type.Name("Service"))
      clientInterface = ScalaType(sdkPackage, serviceName.value + "AsyncClient")
      clientInterfaceBuilder = ScalaType(sdkPackage, serviceName.value + "AsyncClientBuilder")

      serviceMethods <- generateServiceMethods().mapError(GeneratorFailure.CustomFailure.apply)
      ops <- awsModel.getOperations
      javaSdkPaginations <- ZIO.foreach(ops) { case (opName, op) =>
        OperationCollector.get(opName, op).mapError(GeneratorFailure.CustomFailure.apply).map {
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
      serviceMethodMockObjects = serviceMethods.flatMap(
        _.methods.map(_.mockObject)
      )
      serviceMethodMockComposeMethods = serviceMethods.flatMap(
        _.methods.map(_.mockCompose)
      )

      supportsHttp2 = id.name == "kinesis" || id.name == "transcribestreaming"
      supportsHttp2Lit = Lit.Boolean(supportsHttp2)

      mockCompose = q"""
        val compose: zio.URLayer[zio.Has[zio.test.mock.Proxy], $serviceNameT] =
          zio.ZLayer.fromServiceM { proxy =>
            withRuntime.map { rts => 
              new ${Init(serviceTrait, Name.Anonymous(), List.empty)} {
                val api: ${clientInterface.typ} = null
                def withAspect[R1](newAspect: io.github.vigoo.zioaws.core.aspects.AwsCallAspect[R1], r: R1): $serviceTrait = this

                ..$serviceMethodMockComposeMethods
              }
            }
          }
        """

      module = q"""
          import scala.jdk.CollectionConverters._
        
          type $serviceNameT = Has[$serviceTrait]

          object $serviceName {
            trait Service extends io.github.vigoo.zioaws.core.aspects.AspectSupport[Service] {
              val api: ${clientInterface.typ}

              ..$serviceMethodIfaces
            }

            object $serviceNameMock extends zio.test.mock.Mock[$serviceNameT] {
              ..$serviceMethodMockObjects

              $mockCompose
            }
          }

          val live: zio.ZLayer[${Types.awsConfig.typ}, ${Types.throwable.typ}, $serviceNameT] = customized(identity)

          def customized(customization: ${clientInterfaceBuilder.typ} => ${clientInterfaceBuilder.typ}): zio.ZLayer[${Types.awsConfig.typ}, ${Types.throwable.typ}, $serviceNameT] =
            managed(customization).toLayer

          def managed(customization: ${clientInterfaceBuilder.typ} => ${clientInterfaceBuilder.typ}): zio.ZManaged[${Types.awsConfig.typ}, ${Types.throwable.typ}, $serviceTrait] =
            for {
              awsConfig <- zio.ZManaged.service[AwsConfig.Service]
              executor <- ${Types.zio_.term}.executor.toManaged_
              builder = ${clientInterface.term}.builder()
                  .asyncConfiguration(
                    software.amazon.awssdk.core.client.config.ClientAsyncConfiguration
                      .builder()
                      .advancedOption(
                        software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR,
                        executor.asJava
                      )
                      .build()
                  )
              b0 <- awsConfig.configure[${clientInterface.typ}, ${clientInterfaceBuilder.typ}](builder).toManaged_
              b1 <- awsConfig.configureHttpClient[${clientInterface.typ}, ${clientInterfaceBuilder.typ}](b0, ${Types.serviceHttpCapabilities.term}(supportsHttp2 = $supportsHttp2Lit)).toManaged_
              client <- ${Types.zio_.term}(customization(b1).build()).toManaged_
            } yield new $serviceImplT(client, io.github.vigoo.zioaws.core.aspects.AwsCallAspect.identity, ().asInstanceOf[Any])

          private class $serviceImplT[R](override val api: ${clientInterface.typ}, override val aspect: io.github.vigoo.zioaws.core.aspects.AwsCallAspect[R], r: R)
            extends ${Init(serviceTrait, Name.Anonymous(), List.empty)} with io.github.vigoo.zioaws.core.AwsServiceBase[R, $serviceImplT] {
              override val serviceName: ${ScalaType.string.typ} = ${Lit
        .String(serviceName.value)}
              override def withAspect[R1](newAspect: io.github.vigoo.zioaws.core.aspects.AwsCallAspect[R1], r: R1): $serviceImplT[R1] =
                new $serviceImplT(api, newAspect, r)

              ..$serviceMethodImpls
            }

          ..$serviceAccessors
      """
      path <- Generator.generateScalaPackageObject(pkg.parent, pkg.path.head) {
        ZIO.succeed(module)
      }
    } yield path

  protected def generateServiceModule(): ZIO[Has[
    Generator
  ] with AwsGeneratorContext with Blocking, GeneratorFailure[
    AwsGeneratorFailure
  ], Path] =
    for {
      _ <- Generator.setScalaVersion(scalaVersion)
      _ <- Generator.setRoot(config.targetRoot)
      path <- generateServiceModuleCode()
    } yield path

}
