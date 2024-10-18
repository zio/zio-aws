package zio.aws.codegen.generator

import io.github.vigoo.metagen.core._
import zio.aws.codegen.generator.context._
import zio.aws.codegen.generator.context.AwsGeneratorContext._
import zio.aws.codegen.generator.syntax._
import zio.aws.codegen.generator.OperationMethodType._
import software.amazon.awssdk.codegen.model.service.Operation
import zio.nio.file.Path
import zio.{UIO, ZIO}

import java.lang
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
      name <- ZIO.attempt(namingStrategy.getServiceName).mapError(UnknownError)
    } yield ScalaType(pkg, name)

  private def opRequestType(
      opName: String
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] =
    for {
      namingStrategy <- getNamingStrategy
      pkg <- getPkg
      name <- ZIO
        .attempt(namingStrategy.getRequestClassName(opName))
        .mapError(UnknownError)
    } yield ScalaType(pkg / "model", name)

  private def opResponseType(
      opName: String
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ScalaType] =
    for {
      namingStrategy <- getNamingStrategy
      pkg <- getPkg
      name <- ZIO
        .attempt(namingStrategy.getResponseClassName(opName))
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

  private def findShape(
      inputShape: String
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, NamedShape] =
    getModels.flatMap { models =>
      getServiceName.flatMap { serviceName =>
        Option(models.serviceModel().getShape(inputShape)) match {
          case Some(value) => ZIO.succeed(NamedShape(inputShape, value))
          case None => ZIO.fail(UnknownShapeReference(serviceName, inputShape))
        }
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
          requestShape <- Option(op.getInput).flatMap(input =>
            Option(input.getShape)
          ) match {
            case Some(shapeName) => findShape(shapeName).map(Some(_))
            case None            => ZIO.none
          }
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
                javaResponseType,
                requestShape
              )
            case StreamedInputToUnit =>
              generateStreamedInputToUnit(
                serviceNameT,
                methodName,
                requestType,
                responseType,
                javaRequestType,
                javaResponseType,
                requestShape
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
                javaResponseType,
                requestShape
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
      inEventModel = inputEventStream.shape.getMembers.asScala.values.head
      inEventT <- get(inEventModel.getShape).map(_.generatedType)

      outputEventStream <- findEventStreamShape(op.getOutput.getShape)
      outEventModel = outputEventStream.shape.getMembers.asScala.values.head
      outEventT <- get(outEventModel.getShape)
      awsOutEventT <- TypeMapping.toJavaType(outEventT)

      awsOutEventStreamT = ScalaType(modelPkg, outputEventStream.name)
      outEventRoT = outEventT.generatedType / "ReadOnly"
      responseHandlerName = opName + "ResponseHandler"
      responseHandlerT = ScalaType(modelPkg, responseHandlerName)

      wrappedItem <- wrapSdkValue(
        outEventT,
        Term.Name("item")
      )
      opNameLit = Lit.String(opName)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, inEventT)
              .typ}): ${Types
              .zioStreamAwsError(ScalaType.any, outEventRoT)
              .typ}""",
        implementation =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, inEventT)
              .typ}): ${Types
              .zioStreamAwsError(ScalaType.any, outEventRoT)
              .typ} =
                asyncRequestEventInputOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsInEventStreamT.typ}, ${responseHandlerT.typ}, ${awsOutEventStreamT.typ}, ${awsOutEventT.typ}](
                    $opNameLit,
                    (request: ${javaRequestType.typ}, input: ${Types
              .rsPublisher(awsInEventStreamT)
              .typ}, handler: ${responseHandlerT.typ}) => api.$methodName(request, input, handler),
                    (impl: ${Types
              .eventStreamResponseHandler(javaResponseType, awsOutEventStreamT)
              .typ}) =>
                      new ${responseHandlerT.init} {
                        override def responseReceived(response: ${javaResponseType.typ}): ${ScalaType.unit.typ} = impl.responseReceived(response)
                        override def onEventStream(publisher: ${Types
              .sdkPublisher(awsOutEventStreamT)
              .typ}): ${ScalaType.unit.typ} = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: ${Types.throwable.typ}): ${ScalaType.unit.typ} = impl.exceptionOccurred(throwable)
                        override def complete(): ${ScalaType.unit.typ} = impl.complete()
                      })(request.buildAwsValue(), input.map(_.buildAwsValue())).map(item => $wrappedItem).provideEnvironment(r)
               """,
        accessor =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, inEventT)
              .typ}): ${Types.zioStreamAwsError(serviceType, outEventRoT).typ} =
                ${Types.zioStream_.term}.serviceWithStream(_.$methodName(request, input))""",
        mockObject = q"""object $objectName extends Stream[${ScalaType
            .pair(requestType, Types.zioStreamAwsError(ScalaType.any, inEventT))
            .typ}, ${Types.awsError.typ}, ${outEventRoT.typ}]""",
        mockCompose =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, inEventT)
              .typ}): ${Types
              .zioStreamAwsError(ScalaType.any, outEventRoT)
              .typ} = ${unsafeRun(q"""proxy($objectName, request, input)""")}"""
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
      rawEventItem = eventStream.shape.getMembers.asScala.values.head
      eventItemModel <- get(rawEventItem.getShape)
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
        interface = q"""def $methodName(request: ${requestType.typ}): ${Types
            .zioStreamAwsError(ScalaType.any, eventRoT)
            .typ}""",
        implementation =
          q"""def $methodName(request: ${requestType.typ}): ${Types
              .zioStreamAwsError(ScalaType.any, eventRoT)
              .typ} =
                  asyncRequestEventOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}, ${responseHandlerT.typ}, ${awsEventStreamT.typ}, ${awsEventT.typ}](
                    $opNameLit,
                    (request: ${javaRequestType.typ}, handler: ${responseHandlerT.typ}) => api.$methodName(request, handler),
                    (impl: ${Types
              .eventStreamResponseHandler(javaResponseType, awsEventStreamT)
              .typ}) =>
                      new ${responseHandlerT.init} {
                        override def responseReceived(response: ${javaResponseType.typ}): ${ScalaType.unit.typ} = impl.responseReceived(response)
                        override def onEventStream(publisher: ${Types
              .sdkPublisher(awsEventStreamT)
              .typ}): ${ScalaType.unit.typ} = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: ${Types.throwable.typ}): ${ScalaType.unit.typ} = impl.exceptionOccurred(throwable)
                        override def complete(): ${ScalaType.unit.typ} = impl.complete()
                      })(request.buildAwsValue()).map(item => $wrappedItem).provideEnvironment(r)
              """,
        accessor = q"""def $methodName(request: ${requestType.typ}): ${Types
            .zioStreamAwsError(serviceType, eventRoT)
            .typ} =
                ${Types.zioStream_.term}.serviceWithStream(_.$methodName(request))""",
        mockObject =
          q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${eventRoT.typ}]""",
        mockCompose = q"""def $methodName(request: ${requestType.typ}): ${Types
            .zioStreamAwsError(ScalaType.any, eventRoT)
            .typ} = ${unsafeRun(q"""proxy($objectName, request)""")}"""
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
      rawEventItem = eventStream.shape.getMembers.asScala.values.head
      eventT <- get(rawEventItem.getShape).map(_.generatedType)
      opNameLit = Lit.String(methodName.value)
      objectName = Term.Name(methodName.value.capitalize)
    } yield ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, eventT)
              .typ}): ${Types.ioAwsError(responseTypeRo).typ}""",
        implementation =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, eventT)
              .typ}): ${Types.ioAwsError(responseTypeRo).typ} =
                asyncRequestEventInputStream[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsInEventStreamT.typ}]($opNameLit, api.$methodName)(request.buildAwsValue(), input.map(_.buildAwsValue())).provideEnvironment(r)""",
        accessor =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, eventT)
              .typ}): ${Types.zioAwsError(serviceType, responseTypeRo).typ} =
                ${Types.zio_.term}.serviceWithZIO(_.$methodName(request, input))""",
        mockObject = q"""object $objectName extends Effect[${ScalaType
            .pair(requestType, Types.zioStreamAwsError(ScalaType.any, eventT))
            .typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
        mockCompose =
          q"""def $methodName(request: ${requestType.typ}, input: ${Types
              .zioStreamAwsError(ScalaType.any, eventT)
              .typ}): ${Types
              .ioAwsError(responseTypeRo)
              .typ} = proxy($objectName, request, input)"""
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
      javaResponseType: ScalaType,
      requestShape: Option[NamedShape]
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    getContentLengthFromRequest(javaRequestType, requestShape).map {
      getContentLength =>
        ServiceMethods(
          ServiceMethod(
            interface =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .ioAwsError(
                    Types.streamingOutputResult(
                      ScalaType.any,
                      responseTypeRo,
                      ScalaType.byte
                    )
                  )
                  .typ}""",
            implementation =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .ioAwsError(
                    Types.streamingOutputResult(
                      ScalaType.any,
                      responseTypeRo,
                      ScalaType.byte
                    )
                  )
                  .typ} =
                asyncRequestInputOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}](
                  $methodNameLit,
                  api.$methodName[zio.Task[StreamingOutputResult[R, ${javaResponseType.typ}, Byte]]],
                  $getContentLength
                )(request.buildAwsValue(), body)
                  .map(_.mapResponse(${responseType.term}.wrap).provideEnvironment(r))
                  .provideEnvironment(r)
          """, // TODO: needs type param support to ScalaType
            accessor =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .zioAwsError(
                    serviceType,
                    Types.streamingOutputResult(
                      ScalaType.any,
                      responseTypeRo,
                      ScalaType.byte
                    )
                  )
                  .typ} =
                ${Types.zio_.term}.serviceWithZIO(_.$methodName(request, body))""",
            mockObject = q"""object $objectName extends Effect[${ScalaType
                .pair(
                  requestType,
                  Types.zioStreamAwsError(ScalaType.any, ScalaType.byte)
                )
                .typ}, ${Types.awsError.typ}, ${Types
                .streamingOutputResult(
                  ScalaType.any,
                  responseTypeRo,
                  ScalaType.byte
                )
                .typ}]""",
            mockCompose =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .ioAwsError(
                    Types.streamingOutputResult(
                      ScalaType.any,
                      responseTypeRo,
                      ScalaType.byte
                    )
                  )
                  .typ} = proxy($objectName, request, body)"""
          )
        )
    }
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
          interface = q"""def $methodName(request: ${requestType.typ}): ${Types
              .ioAwsError(
                Types.streamingOutputResult(
                  ScalaType.any,
                  responseTypeRo,
                  ScalaType.byte
                )
              )
              .typ}""",
          implementation =
            q"""def $methodName(request: ${requestType.typ}): ${Types
                .ioAwsError(
                  Types.streamingOutputResult(
                    ScalaType.any,
                    responseTypeRo,
                    ScalaType.byte
                  )
                )
                .typ} =
                asyncRequestOutputStream[${javaRequestType.typ}, ${javaResponseType.typ}](
                  $methodNameLit,
                  api.$methodName[zio.Task[StreamingOutputResult[R, ${javaResponseType.typ}, Byte]]]
                )(request.buildAwsValue())
                  .map(_.mapResponse(${responseType.term}.wrap).provideEnvironment(r))
                  .provideEnvironment(r)""", // TODO: needs type param support to ScalaType
          accessor = q"""def $methodName(request: ${requestType.typ}): ${Types
              .zioAwsError(
                serviceType,
                Types.streamingOutputResult(
                  ScalaType.any,
                  responseTypeRo,
                  ScalaType.byte
                )
              )
              .typ} =
                ${Types.zio_.term}.serviceWithZIO(_.$methodName(request))""",
          mockObject =
            q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types
                .streamingOutputResult(
                  ScalaType.any,
                  responseTypeRo,
                  ScalaType.byte
                )
                .typ}]""",
          mockCompose =
            q"""def $methodName(request: ${requestType.typ}): ${Types
                .ioAwsError(
                  Types.streamingOutputResult(
                    ScalaType.any,
                    responseTypeRo,
                    ScalaType.byte
                  )
                )
                .typ} = proxy($objectName, request)"""
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
      javaResponseType: ScalaType,
      requestShape: Option[NamedShape]
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    getContentLengthFromRequest(javaRequestType, requestShape).map {
      getContentLength =>
        ServiceMethods(
          ServiceMethod(
            interface =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types.ioAwsError(responseTypeRo).typ}""",
            implementation =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types.ioAwsError(responseTypeRo).typ} =
              asyncRequestInputStream[${javaRequestType.typ}, ${javaResponseType.typ}](
                $methodNameLit, 
                api.$methodName,
                $getContentLength
              )(request.buildAwsValue(), body).map(${responseType.term}.wrap).provideEnvironment(r)""",
            accessor =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .zioAwsError(serviceType, responseTypeRo)
                  .typ} =
              ${Types.zio_.term}.serviceWithZIO(_.$methodName(request, body))""",
            mockObject = q"""object $objectName extends Effect[${ScalaType
                .pair(
                  requestType,
                  Types.zioStreamAwsError(ScalaType.any, ScalaType.byte)
                )
                .typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
            mockCompose =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .ioAwsError(responseTypeRo)
                  .typ} = proxy($objectName, request, body)"""
          )
        )
    }
  }

  private def generateStreamedInputToUnit(
      serviceType: ScalaType,
      methodName: Term.Name,
      requestType: ScalaType,
      responseType: ScalaType,
      javaRequestType: ScalaType,
      javaResponseType: ScalaType,
      requestShape: Option[NamedShape]
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, ServiceMethods] = {
    val objectName = Term.Name(methodName.value.capitalize)
    val methodNameLit = Lit.String(methodName.value)
    getContentLengthFromRequest(javaRequestType, requestShape).map {
      getContentLength =>
        ServiceMethods(
          ServiceMethod(
            interface =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types.ioAwsError(ScalaType.unit).typ}""",
            implementation =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types.ioAwsError(ScalaType.unit).typ} =
                  asyncRequestInputStream[${javaRequestType.typ}, ${javaResponseType.typ}](
                    $methodNameLit, 
                    api.$methodName,
                    $getContentLength
                  )(
                    request.buildAwsValue(), 
                    body                    
                  ).unit.provideEnvironment(r)""",
            accessor =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .zioAwsError(serviceType, ScalaType.unit)
                  .typ} =
                ${Types.zio_.term}.serviceWithZIO(_.$methodName(request, body))""",
            mockObject = q"""object $objectName extends Effect[${ScalaType
                .pair(
                  requestType,
                  Types.zioStreamAwsError(ScalaType.any, ScalaType.byte)
                )
                .typ}, ${Types.awsError.typ}, ${ScalaType.unit.typ}]""",
            mockCompose =
              q"""def $methodName(request: ${requestType.typ}, body: ${Types
                  .zioStreamAwsError(ScalaType.any, ScalaType.byte)
                  .typ}): ${Types
                  .ioAwsError(ScalaType.unit)
                  .typ} = proxy($objectName, request, body)"""
          )
        )
    }
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

    val simpleMethodName =
      if (pagination.isDefined) Term.Name(methodName.value + "Paginated")
      else methodName
    val simpleObjectName = Term.Name(simpleMethodName.value.capitalize)

    val simpleRequestResponse =
      ServiceMethod(
        interface =
          q"""def $simpleMethodName(request: ${requestType.typ}): ${Types
              .ioAwsError(responseTypeRo)
              .typ}""",
        implementation =
          q"""def $simpleMethodName(request: ${requestType.typ}): ${Types
              .ioAwsError(responseTypeRo)
              .typ} =
                      asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(request.buildAwsValue()).map(${responseType.term}.wrap).provideEnvironment(r)""",
        accessor =
          q"""def $simpleMethodName(request: ${requestType.typ}): ${Types
              .zioAwsError(serviceType, responseTypeRo)
              .typ} =
                ${Types.zio_.term}.serviceWithZIO(_.$simpleMethodName(request))""",
        mockObject =
          q"""object $simpleObjectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
        mockCompose =
          q"""def $simpleMethodName(request: ${requestType.typ}):  ${Types
              .ioAwsError(responseTypeRo)
              .typ} = proxy($simpleObjectName, request)"""
      )

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
              q"""def $methodName(request: ${requestType.typ}): ${Types
                  .zioStreamAwsError(ScalaType.any, wrappedItemType)
                  .typ}""",
            implementation =
              q"""def $methodName(request: ${requestType.typ}): ${Types
                  .zioStreamAwsError(ScalaType.any, wrappedItemType)
                  .typ} =
                    asyncJavaPaginatedRequest[${javaRequestType.typ}, ${awsItemType.typ}, ${publisher.typ}]($methodNameLit, api.$paginatorMethodName, _.${Term
                  .Name(
                    paginationName.uncapitalize
                  )}())(request.buildAwsValue()).map(item => $wrappedItem).provideEnvironment(r)""",
            accessor = q"""def $methodName(request: ${requestType.typ}): ${Types
                .zioStream(serviceType, Types.awsError, wrappedItemType)
                .typ} =
                    ${Types.zioStream_.term}.serviceWithStream(_.$methodName(request))""",
            mockObject =
              q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${wrappedItemType.typ}]""",
            mockCompose =
              q"""def $methodName(request: ${requestType.typ}): ${Types
                  .zioStreamAwsError(ScalaType.any, wrappedItemType)
                  .typ} = ${unsafeRun(q"""proxy($objectName, request)""")}"""
          )
        } yield ServiceMethods(streamedPaginator, simpleRequestResponse)
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
          responseModel <- AwsGeneratorContext.get(responseType.name)
          property <- propertyName(responseModel, listModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(ScalaType.any, itemTypeRo)
                      .typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(ScalaType.any, itemTypeRo)
                      .typ} =
                    asyncSimplePaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsItemType.typ}](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map(item => $wrappedItem).provideEnvironment(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStream(serviceType, Types.awsError, itemTypeRo)
                      .typ} =
                    ${Types.zioStream_.term}.serviceWithStream(_.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${itemTypeRo.typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(ScalaType.any, itemTypeRo)
                      .typ} = ${unsafeRun(
                      q"""proxy($objectName, request)"""
                    )}"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioAwsError(
                        ScalaType.any,
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioAwsError(
                        ScalaType.any,
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ} =
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
                           .provideEnvironment(r))
                      .provideEnvironment(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioAwsError(
                        serviceType,
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ} =
                    ${Types.zio_.term}.serviceWithZIO(_.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types
                      .streamingOutputResult(
                        ScalaType.any,
                        responseTypeRo,
                        itemTypeRo
                      )
                      .typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioAwsError(
                        ScalaType.any,
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ} = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator, simpleRequestResponse)

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
          responseModel <- AwsGeneratorContext.get(responseType.name)
          innerProperty <- propertyName(responseModel, innerModel, innerName)
          innerPropertyNameTerm = Term.Name(innerProperty.javaName)
          listProperty <- propertyName(innerModel, listModel, listName)
          listPropertyNameTerm = Term.Name(listProperty.javaName)
          resultProperty <- propertyName(innerModel, resultModel, resultName)
          resultPropertyNameTerm = Term.Name(resultProperty.javaName)

          paginator = ServiceMethod(
            interface =
              q"""def $methodName(request: ${requestType.typ}): ${Types
                  .ioAwsError(
                    Types.streamingOutputResult(
                      ScalaType.any,
                      resultTypeRo,
                      itemTypeRo
                    )
                  )
                  .typ}""",
            implementation =
              q"""def $methodName(request: ${requestType.typ}): ${Types
                  .ioAwsError(
                    Types.streamingOutputResult(
                      ScalaType.any,
                      resultTypeRo,
                      itemTypeRo
                    )
                  )
                  .typ} =
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
                           .provideEnvironment(r))
                      .provideEnvironment(r)
               """,
            accessor = q"""def $methodName(request: ${requestType.typ}): ${Types
                .zioAwsError(
                  serviceType,
                  Types.streamingOutputResult(
                    ScalaType.any,
                    resultTypeRo,
                    itemTypeRo
                  )
                )
                .typ} =
                    ${Types.zio_.term}.serviceWithZIO(_.$methodName(request))
               """,
            mockObject =
              q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types
                  .streamingOutputResult(
                    ScalaType.any,
                    resultTypeRo,
                    itemTypeRo
                  )
                  .typ}]""",
            mockCompose =
              q"""def  $methodName(request: ${requestType.typ}): ${Types
                  .zioAwsError(
                    ScalaType.any,
                    Types.streamingOutputResult(
                      ScalaType.any,
                      resultTypeRo,
                      itemTypeRo
                    )
                  )
                  .typ} = proxy($objectName, request)"""
          )
        } yield ServiceMethods(paginator, simpleRequestResponse)

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

          responseModel <- AwsGeneratorContext.get(responseType.name)
          property <- propertyName(responseModel, mapModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(
                        ScalaType.any,
                        ScalaType.pair(keyTypeRo, valueTypeRo)
                      )
                      .typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(
                        ScalaType.any,
                        ScalaType.pair(keyTypeRo, valueTypeRo)
                      )
                      .typ} =
                    asyncSimplePaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, (${awsKeyType.typ}, ${awsValueType.typ})](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}.fromIterable(r.$propertyNameTerm().asScala)
                    )(request.buildAwsValue()).map { case (key, value) => $wrappedKey -> $wrappedValue }.provideEnvironment(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(
                        serviceType,
                        ScalaType.pair(keyTypeRo, valueTypeRo)
                      )
                      .typ} =
                    ${Types.zioStream_.term}.serviceWithStream(_.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${ScalaType
                      .pair(keyTypeRo, valueTypeRo)
                      .typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(
                        ScalaType.any,
                        ScalaType.pair(keyTypeRo, valueTypeRo)
                      )
                      .typ} = ${unsafeRun(
                      q"""proxy($objectName, request)"""
                    )}"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioAwsError(
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          ScalaType.pair(keyTypeRo, valueTypeRo)
                        )
                      )
                      .typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioAwsError(
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          ScalaType.pair(keyTypeRo, valueTypeRo)
                        )
                      )
                      .typ} =
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
                          .provideEnvironment(r)
                      }.provideEnvironment(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioAwsError(
                        serviceType,
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          ScalaType.pair(keyTypeRo, valueTypeRo)
                        )
                      )
                      .typ} =
                    ${Types.zio_.term}.serviceWithZIO(_.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types
                      .streamingOutputResult(
                        ScalaType.any,
                        responseTypeRo,
                        ScalaType.pair(keyTypeRo, valueTypeRo)
                      )
                      .typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioAwsError(
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          ScalaType.pair(keyTypeRo, valueTypeRo)
                        )
                      )
                      .typ} = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator, simpleRequestResponse)
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
          responseModel <- AwsGeneratorContext.get(responseType.name)
          property <- propertyName(responseModel, stringModel, memberName)
          propertyNameTerm = Term.Name(property.javaName)
          streamedPaginator =
            if (isSimple) {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioStreamAwsError(itemTypeRo)
                      .typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioStreamAwsError(itemTypeRo)
                      .typ} =
                    asyncSimplePaginatedRequest[${javaRequestType.typ}, ${javaResponseType.typ}, ${awsItemType.typ}](
                      $methodNameLit,
                      api.$methodName,
                      (r, token) => r.toBuilder().nextToken(token).build(),
                      r => ${ScalaType.option_.term}(r.nextToken()),
                      r => ${Types.chunk_.term}(r.$propertyNameTerm())
                    )(request.buildAwsValue()).map(item => $wrappedItem).provideEnvironment(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(serviceType, itemTypeRo)
                      .typ} =
                    ${Types.zioStream_.term}.serviceWithStream(_.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Stream[${requestType.typ}, ${Types.awsError.typ}, ${itemTypeRo.typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioStreamAwsError(ScalaType.any, itemTypeRo)
                      .typ} = ${unsafeRun(
                      q"""proxy($objectName, request)"""
                    )}"""
              )
            } else {
              ServiceMethod(
                interface =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioAwsError(
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ}""",
                implementation =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioAwsError(
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ} =
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
                          .provideEnvironment(r))
                      .provideEnvironment(r)
               """,
                accessor =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .zioAwsError(
                        serviceType,
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ} =
                    ${Types.zio_.term}.serviceWithZIO(_.$methodName(request))
               """,
                mockObject =
                  q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${Types
                      .streamingOutputResult(
                        ScalaType.any,
                        responseTypeRo,
                        itemTypeRo
                      )
                      .typ}]""",
                mockCompose =
                  q"""def $methodName(request: ${requestType.typ}): ${Types
                      .ioAwsError(
                        Types.streamingOutputResult(
                          ScalaType.any,
                          responseTypeRo,
                          itemTypeRo
                        )
                      )
                      .typ} = proxy($objectName, request)"""
              )
            }
        } yield ServiceMethods(streamedPaginator, simpleRequestResponse)
      case None =>
        ZIO.succeed(
          ServiceMethods(
            simpleRequestResponse
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
          interface = q"""def $methodName(request: ${requestType.typ}): ${Types
              .ioAwsError(ScalaType.unit)
              .typ}""",
          implementation =
            q"""def $methodName(request: ${requestType.typ}): ${Types
                .ioAwsError(ScalaType.unit)
                .typ} =
                asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(request.buildAwsValue()).unit.provideEnvironment(r)""",
          accessor = q"""def $methodName(request: ${requestType.typ}): ${Types
              .zioAwsError(serviceType, ScalaType.unit)
              .typ} =
                ${Types.zio_.term}.serviceWithZIO(_.$methodName(request))""",
          mockObject =
            q"""object $objectName extends Effect[${requestType.typ}, ${Types.awsError.typ}, ${ScalaType.unit.typ}]""",
          mockCompose =
            q"""def $methodName(request: ${requestType.typ}): ${Types
                .ioAwsError(ScalaType.unit)
                .typ} = proxy($objectName, request)"""
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
          interface =
            q"""def $methodName(): ${Types.ioAwsError(responseTypeRo).typ}""",
          implementation =
            q"""def $methodName(): ${Types.ioAwsError(responseTypeRo).typ} =
                  asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(${javaRequestType.term}.builder().build()).map(${responseType.term}.wrap).provideEnvironment(r)""",
          accessor = q"""def $methodName(): ${Types
              .zioAwsError(serviceType, responseTypeRo)
              .typ} =
                  ${Types.zio_.term}.serviceWithZIO(_.$methodName())""",
          mockObject =
            q"""object $objectName extends Effect[${ScalaType.unit.typ}, ${Types.awsError.typ}, ${responseTypeRo.typ}]""",
          mockCompose = q"""def $methodName(): ${Types
              .ioAwsError(responseTypeRo)
              .typ} = proxy($objectName)"""
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
          interface =
            q"""def $methodName(): ${Types.ioAwsError(ScalaType.unit).typ}""",
          implementation =
            q"""def $methodName(): ${Types.ioAwsError(ScalaType.unit).typ} =
                asyncRequestResponse[${javaRequestType.typ}, ${javaResponseType.typ}]($methodNameLit, api.$methodName)(${javaRequestType.term}.builder().build()).unit.provideEnvironment(r)""",
          accessor = q"""def $methodName(): ${Types
              .zioAwsError(serviceType, ScalaType.unit)
              .typ} =
                ${Types.zio_.term}.serviceWithZIO(_.$methodName())""",
          mockObject =
            q"""object $objectName extends Effect[${ScalaType.unit.typ}, ${Types.awsError.typ}, ${ScalaType.unit.typ}]""",
          mockCompose = q"""def $methodName(): ${Types
              .ioAwsError(ScalaType.unit)
              .typ} = proxy($objectName)"""
        )
      )
    )
  }

  private def getContentLengthFromRequest(
      javaRequestType: ScalaType,
      requestShape: Option[NamedShape]
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Term] =
    requestShape.flatMap(shape =>
      Option(shape.shape.getMembers.get("ContentLength"))
    ) match {
      case Some(member) =>
        findShape(member.getShape).flatMap { contentLengthShape =>
          if (ModelType.fromShape(contentLengthShape.shape) == ModelType.Long) {
            getLogger.flatMap { logger =>
              ZIO
                .attempt(
                  logger.info(
                    s"Connecting $javaRequestType#ContentLength with the operation's stream length"
                  )
                )
                .ignore
            } *>
              ZIO.succeed(
                q"(r: ${javaRequestType.typ}) => java.util.Optional.ofNullable(r.contentLength())"
              )
          } else {
            ZIO.succeed(
              q"${Types.awsServiceBase.term}.noContentLength[${javaRequestType.typ}]"
            )
          }
        }
      case None =>
        ZIO.succeed(
          q"${Types.awsServiceBase.term}.noContentLength[${javaRequestType.typ}]"
        )
    }

  private def generateServiceModuleCode()
      : ZIO[Generator with AwsGeneratorContext, GeneratorFailure[
        AwsGeneratorFailure
      ], Set[Path]] =
    for {
      id <- getService
      namingStrategy <- getNamingStrategy
      pkg <- getPkg
      sdkPackage <- id.subModuleName match {
        case Some(submodule) if submodule != id.name =>
          getModelPkg.map(_.parent / submodule)
        case _ =>
          getModelPkg.map(_.parent)
      }
      serviceName = Term.Name(namingStrategy.getServiceName)
      serviceNameMock = Term.Name(namingStrategy.getServiceName + "Mock")
      serviceImplName = Term.Name(serviceName.value + "Impl")
      serviceImplT = Type.Name(serviceImplName.value)
      serviceNameT = Type.Name(serviceName.value)
      clientInterface = ScalaType(sdkPackage, serviceName.value + "AsyncClient")
      clientInterfaceBuilder = ScalaType(
        sdkPackage,
        serviceName.value + "AsyncClientBuilder"
      )

      serviceMethods <- generateServiceMethods().mapError(
        GeneratorFailure.CustomFailure.apply
      )
      ops <- awsModel.getOperations
      javaSdkPaginations <- ZIO.foreach(ops) { case (opName, op) =>
        OperationCollector
          .get(opName, op)
          .mapError(GeneratorFailure.CustomFailure.apply)
          .map {
            case RequestResponse(
                  Some(JavaSdkPaginationDefinition(_, _, _, _))
                ) =>
              true
            case _ => false
          }
      }

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

      requiresGlobalRegion =
        Set( // https://docs.aws.amazon.com/general/latest/gr/rande.html
          "cloudfront",
          "globalaccelerator",
          "iam",
          "networkmanager",
          "organizations",
          "route53",
          "shield",
          "waf"
        ).contains(id.name)

      mockCompose = q"""
        val compose: zio.URLayer[zio.mock.Proxy, $serviceNameT] =
          zio.ZLayer {
            zio.ZIO.service[zio.mock.Proxy].flatMap { proxy =>
              withRuntime[zio.mock.Proxy, $serviceNameT] { rts =>
                zio.ZIO.succeed {
                  new ${Init(serviceNameT, Name.Anonymous(), List.empty)} {
                    val api: ${clientInterface.typ} = null
                    def withAspect[R1](newAspect: zio.aws.core.aspects.AwsCallAspect[R1], r: zio.ZEnvironment[R1]): $serviceNameT = this

                    ..$serviceMethodMockComposeMethods
                  }
                }
              }
            }
        }
        """

      module = q"""
          import scala.jdk.CollectionConverters._

          trait $serviceNameT extends zio.aws.core.aspects.AspectSupport[$serviceNameT] {
              val api: ${clientInterface.typ}

              ..$serviceMethodIfaces
          }

          object $serviceName {
            val live: zio.ZLayer[${Types.awsConfig.typ}, ${Types.throwable.typ}, $serviceNameT] = customized(identity)

            def customized(customization: ${clientInterfaceBuilder.typ} => ${clientInterfaceBuilder.typ}): zio.ZLayer[${Types.awsConfig.typ}, ${Types.throwable.typ}, $serviceNameT] =
              ZLayer.scoped(scoped(customization))

          def scoped(customization: ${clientInterfaceBuilder.typ} => ${clientInterfaceBuilder.typ}): zio.ZIO[${Types.awsConfig.typ} with zio.Scope, ${Types.throwable.typ}, $serviceNameT] =
            for {
              awsConfig <- zio.ZIO.service[AwsConfig]
              executor <- ${Types.zio_.term}.executor
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
              b0 <- awsConfig.configure[${clientInterface.typ}, ${clientInterfaceBuilder.typ}](builder)
              b1 <- awsConfig.configureHttpClient[${clientInterface.typ}, ${clientInterfaceBuilder.typ}](b0, ${Types.serviceHttpCapabilities.term}(supportsHttp2 = $supportsHttp2Lit))              
              client <- ${Types.zio_.term}.attempt(customization(${if (
          requiresGlobalRegion
        ) q"b1.region(_root_.software.amazon.awssdk.regions.Region.AWS_GLOBAL)"
        else q"b1"}).build())
            } yield new $serviceImplT(client, zio.aws.core.aspects.AwsCallAspect.identity, zio.ZEnvironment.empty)

            private class $serviceImplT[R](override val api: ${clientInterface.typ}, override val aspect: zio.aws.core.aspects.AwsCallAspect[R], r: zio.ZEnvironment[R])
              extends ${Init(
          serviceNameT,
          Name.Anonymous(),
          List.empty
        )} with zio.aws.core.AwsServiceBase[R] {
                override val serviceName: ${ScalaType.string.typ} = ${Lit
          .String(serviceName.value)}
                override def withAspect[R1](newAspect: zio.aws.core.aspects.AwsCallAspect[R1], r: zio.ZEnvironment[R1]): $serviceImplT[R1] =
                  new $serviceImplT(api, newAspect, r)

                ..$serviceMethodImpls
              }

            ..$serviceAccessors
          }
      """
      path <- Generator.generateScalaPackage(pkg, serviceName.value) {
        ZIO.succeed(module)
      }
      pathMock <- Generator.generateScalaPackage(pkg, serviceNameMock.value) {
        ZIO.succeed {
          q"""{
            object $serviceNameMock extends zio.mock.Mock[$serviceNameT] {
              ..$serviceMethodMockObjects

              $mockCompose
            }
          }"""
        }
      }
    } yield Set(path, pathMock)

  protected def generateServiceModule()
      : ZIO[Generator with AwsGeneratorContext, GeneratorFailure[
        AwsGeneratorFailure
      ], Set[Path]] =
    for {
      _ <- Generator.setScalaVersion(scalaVersion)
      _ <- Generator.setRoot(config.targetRoot)
      paths <- generateServiceModuleCode()
    } yield paths

}
