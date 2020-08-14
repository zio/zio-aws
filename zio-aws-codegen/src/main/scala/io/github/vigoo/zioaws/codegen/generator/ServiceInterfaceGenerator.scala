package io.github.vigoo.zioaws.codegen.generator

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.generator.syntax._
import software.amazon.awssdk.codegen.model.service.Operation
import zio.ZIO

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
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, AwsError, $outEventRoT]]""",
        implementation =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, AwsError, $outEventRoT]] =
                  asyncRequestEventInputOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $awsInEventStreamT, $responseHandlerT, $awsOutEventStreamT, $awsOutEventT](
                    (request: $modelPkg.$requestName, input: Publisher[$awsInEventStreamT], handler: $responseHandlerT) => api.$methodName(request, input, handler),
                    (impl: software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler[$modelPkg.$responseName, $awsOutEventStreamT]) =>
                      new $responseHandlerInit {
                        override def responseReceived(response: $modelPkg.$responseName): Unit = impl.responseReceived(response)
                        override def onEventStream(publisher: software.amazon.awssdk.core.async.SdkPublisher[$awsOutEventStreamT]): Unit = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: Throwable): Unit = impl.exceptionOccurred(throwable)
                        override def complete(): Unit = impl.complete()
                      })(request.buildAwsValue(), input.map(_.buildAwsValue())).map(_.map(item => $wrappedItem))
               """,
        accessor =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $outEventRoT]] =
                ZIO.accessM(_.get.$methodName(request, input))"""
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
          q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $eventRoT]]""",
        implementation =
          q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $eventRoT]] =
                  asyncRequestEventOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $responseHandlerT, $awsEventStreamT, $awsEventT](
                    (request: $modelPkg.$requestName, handler: $responseHandlerT) => api.$methodName(request, handler),
                    (impl: software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler[$modelPkg.$responseName, $awsEventStreamT]) =>
                      new $responseHandlerInit {
                        override def responseReceived(response: $modelPkg.$responseName): Unit = impl.responseReceived(response)
                        override def onEventStream(publisher: software.amazon.awssdk.core.async.SdkPublisher[$awsEventStreamT]): Unit = impl.onEventStream(publisher)
                        override def exceptionOccurred(throwable: Throwable): Unit = impl.exceptionOccurred(throwable)
                        override def complete(): Unit = impl.complete()
                      })(request.buildAwsValue()).map(_.map(item => $wrappedItem))
              """,
        accessor =
          q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $eventRoT]] =
                ZIO.accessM(_.get.$methodName(request))"""
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
                asyncRequestEventInputStream[$modelPkg.$requestName, $modelPkg.$responseName, $awsInEventStreamT](api.$methodName)(request.buildAwsValue(), input.map(_.buildAwsValue()))""",
        accessor =
          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $eventT]): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName(request, input))"""))
  }

  private def generateStreamedInputOutput(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseTypeRo]]""",
        implementation =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseTypeRo]] =
                asyncRequestInputOutputStream[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName[zio.Task[StreamingOutputResult[$modelPkg.$responseName]]])(request.buildAwsValue(), body).map(_.map($responseNameTerm.wrap))""",
        accessor =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseTypeRo]] =
                ZIO.accessM(_.get.$methodName(request, body))"""
      )))
  }

  private def generateStreamedOutput(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseTypeRo]]""",
        implementation =
          q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseTypeRo]] =
                asyncRequestOutputStream[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName[zio.Task[StreamingOutputResult[$modelPkg.$responseName]]])(request.buildAwsValue()).map(_.map($responseNameTerm.wrap))""",
        accessor =
          q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseTypeRo]] =
                ZIO.accessM(_.get.$methodName(request))"""
      )))
  }

  private def generateStreamedInput(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, $responseTypeRo]""",
        implementation =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, $responseTypeRo] =
                asyncRequestInputStream[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)(request.buildAwsValue(), body).map($responseNameTerm.wrap)""",
        accessor =
          q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
                ZIO.accessM(_.get.$methodName(request, body))"""
      )))
  }

  private def generateRequestToResponse(opName: String, serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, responseNameTerm: Term.Name, responseTypeRo: Type.Select, modelPkg: Term.Ref, pagination: Option[PaginationDefinition]) = {
    val raw = ServiceMethod(
      interface =
        q"""def $methodName(request: $requestName): IO[AwsError, $responseTypeRo]""",
      implementation =
        q"""def $methodName(request: $requestName): IO[AwsError, $responseTypeRo] =
              asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)(request.buildAwsValue()).map($responseNameTerm.wrap)""",
      accessor =
        q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, $responseTypeRo] =
              ZIO.accessM(_.get.$methodName(request))"""
    )
    pagination match {
      case Some(pagination) =>
        for {
          paginatorPkg <- getPaginatorPkg
          methodNameStream = Term.Name(methodName.value + "Stream")
          wrappedItemType = pagination.wrappedTypeRo
          paginatorMethodName = Term.Name(methodName.value + "Paginator")
          publisherType = Type.Name(opName + "Publisher")
          wrappedItem <- wrapSdkValue(pagination.model, Term.Name("item"))
          awsItemType <- TypeMapping.toJavaType(pagination.model)
          streamedPaginator = ServiceMethod(
            interface =
              q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $wrappedItemType]]""",
            implementation =
              q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $wrappedItemType]] =
                                      asyncPaginatedRequest[$modelPkg.$requestName, $awsItemType, $paginatorPkg.$publisherType](api.$paginatorMethodName, _.${Term.Name(pagination.name.uncapitalize)}())(request.buildAwsValue()).map(_.map(item => $wrappedItem))""",
            accessor =
              q"""def $methodNameStream(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $wrappedItemType]] =
                                      ZIO.accessM(_.get.$methodNameStream(request))"""
          )
        } yield ServiceMethods(raw, streamedPaginator)
      case None =>
        ZIO.succeed(ServiceMethods(raw))
    }
  }

  private def generateRequestToUnit(serviceNameT: Type.Name, methodName: Term.Name, requestName: Type.Name, responseName: Type.Name, modelPkg: Term.Ref) = {
    ZIO.succeed(ServiceMethods(
      ServiceMethod(
        interface =
          q"""def $methodName(request: $requestName): IO[AwsError, scala.Unit]""",
        implementation =
          q"""def $methodName(request: $requestName): IO[AwsError, scala.Unit] =
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)(request.buildAwsValue()).unit""",
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
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)($modelPkg.$requestNameTerm.builder().build()).map($responseNameTerm.wrap)""",
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
                asyncRequestResponse[$modelPkg.$requestName, $modelPkg.$responseName](api.$methodName)($modelPkg.$requestNameTerm.builder().build()).unit""",
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
      serviceNameT = Type.Name(serviceName.value)
      serviceTrait = Type.Select(serviceName, Type.Name("Service"))
      clientInterface = Type.Name(serviceName.value + "AsyncClient")
      clientInterfaceSingleton = Term.Name(serviceName.value + "AsyncClient")
      clientInterfaceBuilder = Type.Name(serviceName.value + "AsyncClientBuilder")

      serviceMethods <- generateServiceMethods()
      ops <- awsModel.getOperations
      paginations <- ZIO.foreach(ops) { case (opName, op) =>
        OperationCollector.get(opName, op).map {
          case RequestResponse(Some(_)) => true
          case _ => false
        }
      }
      hasPaginators = paginations.exists(identity)

      serviceMethodIfaces = serviceMethods.flatMap(_.methods.map(_.interface))
      serviceMethodImpls = serviceMethods.flatMap(_.methods.map(_.implementation))
      serviceAccessors = serviceMethods.flatMap(_.methods.map(_.accessor))

      imports = List(
        Some(q"""import io.github.vigoo.zioaws.core._"""),
        Some(q"""import io.github.vigoo.zioaws.core.config.AwsConfig"""),
        if (hasPaginators)
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
            } yield new ${Init(serviceTrait, Name.Anonymous(), List.empty)} with AwsServiceBase {
              val api: $clientInterface = client

              ..$serviceMethodImpls
            }).toLayer

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

  protected def generateServiceModule(): ZIO[GeneratorContext, GeneratorFailure, Unit] =
    for {
      code <- generateServiceModuleCode()
      id <- getService
      moduleName = id.moduleName
      moduleRoot = config.parameters.targetRoot.resolve(moduleName)
      scalaRoot = moduleRoot.resolve("src/main/scala")
      packageParent = scalaRoot.resolve("io/github/vigoo/zioaws")
      packageRoot = packageParent.resolve(moduleName)
      moduleFile = packageRoot.resolve("package.scala")
      _ <- ZIO(Files.createDirectories(packageRoot)).mapError(FailedToCreateDirectories)
      _ <- writeIfDifferent(moduleFile, code)
    } yield ()

}
