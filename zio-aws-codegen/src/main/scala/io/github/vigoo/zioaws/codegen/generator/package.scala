package io.github.vigoo.zioaws.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.clipp.zioapi.config.ClippConfig
import io.github.vigoo.zioaws.codegen.loader.ModelId
import io.github.vigoo.zioaws.codegen.generator.context._

import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.internal.Utils
import software.amazon.awssdk.codegen.model.config.customization.{CustomizationConfig, ShapeModifier}
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.{DefaultNamingStrategy, NamingStrategy}
import software.amazon.awssdk.core.util.VersionInfo
import zio._
import zio.console.Console

import scala.jdk.CollectionConverters._
import scala.meta.Term

package object generator {
  type Generator = Has[Generator.Service]

  object Generator {

    trait Service {
      def generateServiceCode(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit]

      def generateBuildSbt(ids: Set[ModelId]): ZIO[Console, GeneratorFailure, Unit]

      def copyCoreProject(): ZIO[Console, GeneratorFailure, Unit]
    }

  }

  val live: ZLayer[ClippConfig[Parameters], Nothing, Generator] = ZLayer.fromService { config =>
    new Generator.Service {

      import _root_.io.github.vigoo.zioaws.codegen.generator.syntax._

      import scala.meta._

      private def opMethodName(opName: String): Term.Name =
        Term.Name(opName.toCamelCase)

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

      // TODO: remove
      private def safeTypeName(typ: Type): ZIO[GeneratorContext, Nothing, Type] =
        for {
          modelPkg <- getModelPkg
          customizationConfig <- getModels.map(_.customizationConfig())
        } yield typ match {
          case Type.Name("Service") =>
            Type.Select(modelPkg, Type.Name("Service"))
          case Type.Name(name) =>
            Option(customizationConfig.getRenameShapes).map(_.asScala).getOrElse(Map.empty[String, String]).get(name) match {
              case Some(value) =>
                Type.Name(value)
              case None =>
                typ
            }
          case _ =>
            typ
        }

      private def generateServiceMethods(): ZIO[GeneratorContext, GeneratorFailure, List[ServiceMethods]] = {
        getModels.flatMap { models =>
          ZIO.foreach(OperationCollector.getFilteredOperations(models).toList) { case (opName, op) =>
            for {
              namingStrategy <- getNamingStrategy
              methodName = opMethodName(opName)

              modelMap <- getModelMap
              serviceNameT = Type.Name(namingStrategy.getServiceName)
              requestName <- opRequestName(opName)
              requestNameTerm <- opRequestNameTerm(opName)
              responseName <- opResponseName(opName)
              responseNameTerm <- opResponseNameTerm(opName)
              responseTypeRo = Type.Select(responseNameTerm, Type.Name("ReadOnly"))
              modelPkg <- getModelPkg
              operation <- OperationCollector.get(opName, op)
              result <- operation match {
                case UnitToUnit =>
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
                case UnitToResponse =>
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
                case RequestToUnit =>
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
                case RequestResponse(pagination) =>
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
                        itemType <- safeTypeName(pagination.itemType)
                        paginatorPkg <- getPaginatorPkg
                        methodNameStream = Term.Name(methodName.value + "Stream")
                        wrappedItemType = pagination.wrappedTypeRo
                        paginatorMethodName = Term.Name(methodName.value + "Paginator")
                        publisherType = Type.Name(opName + "Publisher")
                        wrappedItem <- wrapSdkValue(pagination.model, Term.Name("item"))
                        awsItemType <- TypeMapping.toType(pagination.model)
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
                case StreamedInput =>
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
                case StreamedOutput =>
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
                case StreamedInputOutput =>
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
                case EventStreamInput =>
                  for {
                    modelMap <- getModelMap
                    eventStream <- findEventStreamShape(op.getInput.getShape)
                    awsInEventStreamT = Type.Select(modelPkg, Type.Name(eventStream.name))
                    rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
                    eventItemModel <- modelMap.get(rawEventItemName)
                    eventT <- safeTypeName(Type.Name(rawEventItemName))
                    awsEventT <- TypeMapping.toType(eventItemModel)
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

                case EventStreamOutput =>
                  for {
                    modelMap <- getModelMap
                    eventStream <- findEventStreamShape(op.getOutput.getShape)
                    awsEventStreamT = Type.Select(modelPkg, Type.Name(eventStream.name))
                    rawEventItemName = eventStream.shape.getMembers.asScala.keys.head
                    eventItemModel <- modelMap.get(rawEventItemName)
                    eventT <- safeTypeName(Type.Name(rawEventItemName))
                    eventRoT = Type.Select(Term.Name(eventItemModel.name), Type.Name("ReadOnly"))
                    awsEventT <- TypeMapping.toType(eventItemModel)
                    responseHandlerName = opName + "ResponseHandler"
                    responseHandlerTerm = Term.Select(modelPkg, Term.Name(responseHandlerName))
                    responseHandlerT = Type.Select(modelPkg, Type.Name(responseHandlerName))
                    visitorInit = Init(Type.Select(responseHandlerTerm, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                    visitor: Term.NewAnonymous =
                    q"""new $visitorInit {
                          override def visit(event: $awsEventT): Unit = runtime.unsafeRun(queue.offer(event))
                        }"""

                    wrappedItem <- wrapSdkValue(eventItemModel, Term.Name("item"))
                  } yield ServiceMethods(
                            ServiceMethod(
                              interface =
                                q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $eventRoT]]""",
                              implementation =
                                q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $eventRoT]] =
                          ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                            asyncRequestEventOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $responseHandlerT, $awsEventStreamT, $awsEventT](
                              (request: $modelPkg.$requestName, handler: $responseHandlerT) => api.$methodName(request, handler),
                              (queue: zio.Queue[$awsEventT]) =>
                                $responseHandlerTerm.builder()
                                  .subscriber($visitor)
                                  .build()
                            )(request.buildAwsValue()).map(_.map(item => $wrappedItem))
                          }
                        """,
                              accessor =
                                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $eventRoT]] =
                            ZIO.accessM(_.get.$methodName(request))"""
                            ))
                case EventStreamInputOutput => // TODO: extract common parts of EventStreamInput/Output/InputOutput
                  for {
                    modelMap <- getModelMap
                    inputEventStream <- findEventStreamShape(op.getInput.getShape)
                    awsInEventStreamT = Type.Select(modelPkg, Type.Name(inputEventStream.name))
                    inEventShapeName = inputEventStream.shape.getMembers.asScala.keys.head
                    inEventT <- safeTypeName(Type.Name(inEventShapeName))
                    inEventModel <- modelMap.get(inEventShapeName)
                    awsInEventT <- TypeMapping.toType(inEventModel)

                    outputEventStream <- findEventStreamShape(op.getOutput.getShape)
                    outEventShapeName = outputEventStream.shape.getMembers.asScala.keys.head
                    outEventModel <- modelMap.get(outEventShapeName)
                    awsOutEventT <- TypeMapping.toType(outEventModel)

                    awsOutEventStreamT = Type.Select(modelPkg, Type.Name(outputEventStream.name))
                    outEventT <- safeTypeName(Type.Name(outEventShapeName))
                    outEventRoT = Type.Select(Term.Name(outEventModel.name), Type.Name("ReadOnly"))
                    responseHandlerName = opName + "ResponseHandler"
                    responseHandlerTerm = Term.Select(modelPkg, Term.Name(responseHandlerName))
                    responseHandlerT = Type.Select(modelPkg, Type.Name(responseHandlerName))
                    visitorInit = Init(Type.Select(responseHandlerTerm, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                    visitor: Term.NewAnonymous =
                      q"""new $visitorInit {
                            override def visit(event: $awsOutEventT): Unit = runtime.unsafeRun(queue.offer(event))
                          }"""

                    wrappedItem <- wrapSdkValue(outEventModel, Term.Name("item"))
                  } yield ServiceMethods(
                            ServiceMethod(
                              interface =
                                q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, AwsError, $outEventRoT]]""",
                              implementation =
                                q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, AwsError, $outEventRoT]] =
                           ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                             asyncRequestEventInputOutputStream[$modelPkg.$requestName, $modelPkg.$responseName, $awsInEventStreamT, $responseHandlerT, $awsOutEventStreamT, $awsOutEventT](
                               (request: $modelPkg.$requestName, input: Publisher[$awsInEventStreamT], handler: $responseHandlerT) => api.$methodName(request, input, handler),
                               (queue: zio.Queue[$awsOutEventT]) =>
                                 $responseHandlerTerm.builder()
                                   .subscriber($visitor)
                                   .build())(request.buildAwsValue(), input.map(_.buildAwsValue())).map(_.map(item => $wrappedItem))
                           }""",
                              accessor =
                                q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $outEventRoT]] =
                      ZIO.accessM(_.get.$methodName(request, input))"""
                            ))
              }
            } yield result
          }
        }
      }

      private def generateServiceModuleCode(): ZIO[GeneratorContext, GeneratorFailure, String] = {
        getService.flatMap { id =>
          getModels.flatMap { model =>
            getNamingStrategy.flatMap { namingStrategy =>
              getPaginatorPkg.flatMap { paginatorPackage =>
                val pkgName = Term.Name(id.moduleName)
                val serviceName = Term.Name(namingStrategy.getServiceName)
                val serviceNameT = Type.Name(serviceName.value)
                val serviceTrait = Type.Select(serviceName, Type.Name("Service"))
                val clientInterface = Type.Name(serviceName.value + "AsyncClient")
                val clientInterfaceSingleton = Term.Name(serviceName.value + "AsyncClient")
                val clientInterfaceBuilder = Type.Name(serviceName.value + "AsyncClientBuilder")

                generateServiceMethods()
                  .flatMap { serviceMethods =>
                    ZIO.foreach(model.serviceModel().getOperations.asScala.toList) { case (opName, op) =>
                      OperationCollector.get(opName, op).map {
                        case RequestResponse(Some(_)) => true
                        case _ => false
                      }
                    }.map { paginations =>
                      val hasPaginators = paginations.exists(identity)

                      val serviceMethodIfaces = serviceMethods.flatMap(_.methods.map(_.interface))
                      val serviceMethodImpls = serviceMethods.flatMap(_.methods.map(_.implementation))
                      val serviceAccessors = serviceMethods.flatMap(_.methods.map(_.accessor))

                      val imports = List(

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

                      val module =
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

                          val live: ZLayer[AwsConfig, Throwable, $serviceNameT] =
                            (for {
                              awsConfig <- ZIO.service[AwsConfig.Service]
                              b0 <- awsConfig.configure[$clientInterface, $clientInterfaceBuilder]($clientInterfaceSingleton.builder())
                              b1 <- awsConfig.configureHttpClient[$clientInterface, $clientInterfaceBuilder](b0)
                              client <- ZIO(b1.build())
                            } yield new ${Init(serviceTrait, Name.Anonymous(), List.empty)} with AwsServiceBase {
                              val api: $clientInterface = client

                              ..$serviceMethodImpls
                            }).toLayer

                          ..$serviceAccessors
                        }
                      """

                      val pkg =
                        q"""package io.github.vigoo.zioaws {
                              ..$imports
                              $module
                            }
                      """
                      pkg.toString
                    }
                  }
              }
            }
          }
        }
      }

      private def getSdkModelPackage(id: ModelId, pkgName: Term.Name) = {
        val modelPkg: Term.Ref = id.subModuleName match {
          case Some(submodule) if submodule != id.name =>
            q"software.amazon.awssdk.services.${Term.Name(id.name)}.model"
          case _ =>
            q"software.amazon.awssdk.services.$pkgName.model"
        }
        modelPkg
      }

      private def getSdkPaginatorPackage(id: loader.ModelId, pkgName: Term.Name) = {
        id.subModuleName match {
          case Some(submodule) if submodule.nonEmpty =>
            q"software.amazon.awssdk.services.${Term.Name(id.name)}.${Term.Name(submodule)}.paginators"
          case _ =>
            q"software.amazon.awssdk.services.$pkgName.paginators"
        }
      }

      private def generateBuildSbtCode(ids: Set[ModelId]): String = {
        val versionStr = Lit.String(config.parameters.version)
        val projects = ids.toList.map { id =>
          val name = Term.Name(id.moduleName)
          val nameStr = Lit.String(id.moduleName)
          val fullNameStr = Lit.String(s"zio-aws-${id.moduleName}")
          val artifactStr = Lit.String(id.name)
          val nameP = Pat.Var(name)

          val deps =
            id.subModule match {
              case Some(value) if value != id.name =>
                val baseProject = Term.Name(id.name)
                List(Term.Name("core"), baseProject)
              case _ =>
                List(Term.Name("core"))
            }

          q"""lazy val $nameP = Project($fullNameStr, file($nameStr)).settings(commonSettings).settings(
                libraryDependencies += "software.amazon.awssdk" % $artifactStr % awsVersion,
                publishArtifact in (Compile, packageDoc) := false
              ).dependsOn(..$deps)
           """
        }
        val awsVersionStr = Lit.String(VersionInfo.SDK_VERSION)
        val zioVersionStr = Lit.String(config.parameters.zioVersion)
        val zioReactiveStreamsInteropVersionStr = Lit.String(config.parameters.zioInteropReactiveStreamsVersion)

        val code =
          q"""
              val awsVersion = $awsVersionStr

              publishArtifact := false

              lazy val commonSettings = Seq(
                scalaVersion := "2.13.3",
                organization := "io.github.vigoo",
                version := $versionStr,
                libraryDependencies ++= Seq(
                  "dev.zio" %% "zio" % $zioVersionStr,
                  "dev.zio" %% "zio-streams" % $zioVersionStr,
                  "dev.zio" %% "zio-interop-reactivestreams" % $zioReactiveStreamsInteropVersionStr
                )
              )

              lazy val core = Project("zio-aws-core", file("zio-aws-core")).settings(commonSettings).settings(
                libraryDependencies ++= Seq(
                  "software.amazon.awssdk" % "aws-core" % awsVersion
                )
              )

              ..$projects
           """
        code.toString.stripPrefix("{").stripSuffix("}")
      }

      private def removeDuplicates(modelSet: Set[Model]): Set[Model] =
        modelSet
          .foldLeft(Map.empty[String, Model]) { case (result, model) => result.updated(model.name, model) }
          .values
          .toSet

      private def filterModels(models: C2jModels, modelSet: Set[Model]): Set[Model] = {
        val excluded =
          Option(models.customizationConfig.getShapeModifiers)
            .map(_.asScala.collect { case (name, modifier) if modifier.isExcludeShape => name }.toSet)
            .getOrElse(Set.empty)

        modelSet.filterNot { model =>
          excluded.contains(model.serviceModelName) ||
          model.shape.isException ||
          model.shape.isEventStream ||
          TypeMapping.isBuiltIn(model.shapeName)
        }
      }

      private def unwrapSdkValue(model: Model, term: Term, forceToString: Boolean = false): ZIO[GeneratorContext, GeneratorFailure, Term] =
        model.typ match {
          case ModelType.Map =>
            for {
              modelMap <- getModelMap
              keyModel <- modelMap.get(model.shape.getMapKeyType.getShape)
              valueModel <- modelMap.get(model.shape.getMapValueType.getShape)
              key = Term.Name("key")
              value = Term.Name("value")
              unwrapKey <- unwrapSdkValue(keyModel, key, forceToString = true)
              unwrapValue <- unwrapSdkValue(valueModel, value, forceToString = true)
            } yield if (unwrapKey == key && unwrapValue == value) {
              q"""$term.asJava"""
            } else {
              q"""$term.map { case (key, value) => $unwrapKey -> $unwrapValue }.asJava"""
            }
          case ModelType.List =>
            for {
              modelMap <- getModelMap
              valueModel <- modelMap.get(model.shape.getListMember.getShape)
              item = Term.Name("item")
              unwrapItem <- unwrapSdkValue(valueModel, item, forceToString = true)
            } yield if (unwrapItem == item) {
              q"""$term.asJava"""
            } else {
              q"""$term.map { item => $unwrapItem }.asJava"""
            }
          case ModelType.Enum =>
            val nameTerm = Term.Name(model.name)
            if (forceToString) {
              ZIO.succeed(q"""$term.unwrap.toString""")
            } else {
              ZIO.succeed(q"""$term.unwrap""")
            }
          case ModelType.Blob =>
            ZIO.succeed(q"""SdkBytes.fromByteArray($term.toArray[Byte])""")
          case ModelType.Structure =>
            ZIO.succeed(q"""$term.buildAwsValue()""")
          case ModelType.Exception =>
            ZIO.succeed(term)
          case ModelType.BigDecimal =>
            ZIO.succeed(q"""$term.bigDecimal""")
          case _ =>
            TypeMapping.toType(model).map { typ =>
              q"""$term : $typ"""
            }
        }

      private def wrapSdkValue(model: Model, term: Term): ZIO[GeneratorContext, GeneratorFailure, Term] =
        model.typ match {
          case ModelType.Map =>
            for {
              modelMap <- getModelMap
              keyModel <- modelMap.get(model.shape.getMapKeyType.getShape)
              valueModel <- modelMap.get(model.shape.getMapValueType.getShape)
              key = Term.Name("key")
              value = Term.Name("value")
              wrapKey <- wrapSdkValue(keyModel, key)
              wrapValue <- wrapSdkValue(valueModel, value)
            } yield if (wrapKey == key && wrapValue == value) {
              q"""$term.asScala.toMap"""
            } else {
              q"""$term.asScala.map { case (key, value) => $wrapKey -> $wrapValue }.toMap"""
            }
          case ModelType.List =>
            for {
              modelMap <- getModelMap
              valueModel <- modelMap.get(model.shape.getListMember.getShape)
              item = Term.Name("item")
              wrapItem <- wrapSdkValue(valueModel, item)
            } yield if (wrapItem == item) {
              q"""$term.asScala.toList"""
            } else {
              q"""$term.asScala.map { item => $wrapItem }.toList"""
            }
          case ModelType.Enum =>
            val nameTerm = Term.Name(model.name)
            ZIO.succeed(q"""$nameTerm.wrap($term)""")
          case ModelType.Blob =>
            ZIO.succeed(q"""Chunk.fromByteBuffer($term.asByteBuffer())""")
          case ModelType.Structure =>
            val nameTerm = Term.Name(model.name)
            ZIO.succeed(q"""$nameTerm.wrap($term)""")
          case ModelType.Exception =>
            ZIO.succeed(term)
          case _ if TypeMapping.isPrimitiveType(model.shape) && !TypeMapping.isBuiltIn(model.shapeName) =>
            ZIO.succeed(q"""$term : primitives.${Type.Name(model.name)}""")
          case _ =>
            ZIO.succeed(q"""$term : ${Type.Name(model.name)}""")
        }

      private def filterMembers(shape: String, members: List[(String, software.amazon.awssdk.codegen.model.service.Member)]): ZIO[GeneratorContext, Nothing, List[(String, software.amazon.awssdk.codegen.model.service.Member)]] = {
        getModels.flatMap { models =>
          val shapeModifiers = Option(models.customizationConfig().getShapeModifiers).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
          val global = shapeModifiers.get("*")
          val local = shapeModifiers.get(shape)
          val globalExcludes = global.flatMap(g => Option(g.getExclude)).map(_.asScala).getOrElse(List.empty).map(_.toLowerCase)
          val localExcludes = local.flatMap(l => Option(l.getExclude)).map(_.asScala).getOrElse(List.empty).map(_.toLowerCase)

          ZIO.succeed(members.filterNot { case (memberName, member) =>
            globalExcludes.contains(memberName.toLowerCase) ||
              localExcludes.contains(memberName.toLowerCase) ||
              member.isStreaming || {
              val shape = models.serviceModel().getShape(member.getShape)
              shape.isStreaming || shape.isEventStream
            }
          })
        }
      }

      private def propertyName(namingStrategy: NamingStrategy, models: C2jModels, model: Model, fieldModel: Model, name: String): String = {
        val shapeModifiers = Option(models.customizationConfig().getShapeModifiers).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
        shapeModifiers.get(model.shapeName).flatMap { shapeModifier =>
          val modifies = Option(shapeModifier.getModify).map(_.asScala).getOrElse(List.empty)
          val matchingModifiers = modifies.flatMap { modifiesMap =>
            modifiesMap.asScala.map { case (key, value) => (key.toLowerCase, value) }.get(name.toLowerCase)
          }.toList

          matchingModifiers
            .map(modifier => Option(modifier.getEmitPropertyName))
            .find(_.isDefined)
            .flatten.map(_.uncapitalize)
        }.getOrElse {
          val getterMethod = namingStrategy.getFluentGetterMethodName(name, model.shape, fieldModel.shape)
          getterMethod
            .stripSuffix("AsString")
            .stripSuffix("AsStrings")
        }
      }

      private def applyEnumModifiers(models: C2jModels, model: Model, enumValueList: List[String]): List[String] = {
        val shapeModifiers = Option(models.customizationConfig().getShapeModifiers).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
        shapeModifiers.get(model.shapeName) match {
          case Some(shapeModifier) =>
            val renames = Option(shapeModifier.getModify)
              .map(_.asScala.flatMap(_.asScala.toList))
              .getOrElse(List.empty)
              .toMap

            enumValueList.map { enumValue =>
              renames.get(enumValue) match {
                case Some(modifier) if Option(modifier.getEmitEnumName).isDefined =>
                  modifier.getEmitEnumName
                case _ => enumValue
              }
            }
          case None =>
            enumValueList
        }
      }

      private def adjustFieldType(models: C2jModels, model: Model, fieldName: String, fieldModel: Model): Model = {
        val shapeModifiers = Option(models.customizationConfig().getShapeModifiers).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
        shapeModifiers.get(model.shapeName).flatMap { shapeModifier =>
          val modifies = Option(shapeModifier.getModify).map(_.asScala).getOrElse(List.empty)
          val matchingModifiers = modifies.flatMap { modifiesMap =>
            modifiesMap.asScala.map { case (key, value) => (key.toLowerCase, value) }.get(fieldName.toLowerCase)
          }.toList

          matchingModifiers
            .map(modifier => Option(modifier.getEmitAsType))
            .find(_.isDefined)
            .flatten
            .map(ModelType.fromString)
            .map {
              newTyp =>
                val resetedName = newTyp match {
                  case ModelType.String => "String"
                  case ModelType.Integer => "Int"
                  case ModelType.Long => "Long"
                  case ModelType.Float => "Float"
                  case ModelType.Double => "Double"
                  case ModelType.Boolean => "Boolean"
                  case ModelType.Timestamp => "Instant"
                  case ModelType.BigDecimal => "BigDecimal"
                  case ModelType.Blob => "Chunk[Byte]"
                  case _ => fieldModel.name
                }
                fieldModel.copy(
                  name = resetedName,
                  shapeName = resetedName,
                  typ = newTyp
                )
            }
        }.getOrElse(fieldModel)
      }

      private def roToEditable(model: Model, term: Term): ZIO[GeneratorContext, GeneratorFailure, Term] =
        model.typ match {
          case ModelType.Map =>
            for {
              modelMap <- getModelMap
              keyModel <- modelMap.get(model.shape.getMapKeyType.getShape)
              valueModel <- modelMap.get(model.shape.getMapValueType.getShape)
              key = Term.Name("key")
              value = Term.Name("value")
              keyToEditable <- roToEditable(keyModel, key)
              valueToEditable <- roToEditable(valueModel, value)
            } yield if (keyToEditable == key && valueToEditable == value) {
              term
            } else {
              q"""$term.map { case (key, value) => $keyToEditable -> $valueToEditable }"""
            }
          case ModelType.List =>
            for {
              modelMap <- getModelMap
              valueModel <- modelMap.get(model.shape.getListMember.getShape)
              item = Term.Name("item")
              itemToEditable <- roToEditable(valueModel, item)
            } yield if (itemToEditable == item) {
              term
            } else {
              q"""$term.map { item => $itemToEditable }"""
            }
          case ModelType.Structure =>
            ZIO.succeed(q"""$term.editable""")
          case _ =>
            ZIO.succeed(term)
        }

      private def generateModel(m: Model): ZIO[GeneratorContext, GeneratorFailure, ModelWrapper] = {
        val shapeName = m.name
        val shape = m.shape
        val shapeNameT = Type.Name(shapeName)
        val shapeNameTerm = Term.Name(shapeName)

        for {
          modelMap <- getModelMap
          models <- getModels
          namingStrategy <- getNamingStrategy
          awsShapeNameT <- TypeMapping.toType(m)
          awsShapeNameTerm = awsShapeNameT match {
            case Type.Select(term, Type.Name(name)) => Term.Select(term, Term.Name(name))
            case _ => Term.Name(m.name)
          }
          wrapper <- m.typ match {
            case ModelType.Structure =>
              val roT = Type.Name("ReadOnly")
              val shapeNameRoT = Type.Select(shapeNameTerm, roT)
              val shapeNameRoInit = Init(shapeNameRoT, Name.Anonymous(), List.empty)

              for {
                fieldList <- filterMembers(shapeName, shape.getMembers.asScala.toList)
                required = Option(shape.getRequired).map(_.asScala.toSet).getOrElse(Set.empty)
                fields <- ZIO.foreach(fieldList) {
                  case (memberName, member) =>
                    modelMap.get(member.getShape).flatMap { fieldModel =>
                      val finalFieldModel = adjustFieldType(models, m, memberName, fieldModel)
                      val property = propertyName(namingStrategy, models, m, finalFieldModel, memberName)
                      val propertyNameTerm = Term.Name(property)
                      val propertyNameP = Pat.Var(propertyNameTerm)
                      val fluentSetter = Term.Name(namingStrategy.getFluentSetterMethodName(property, m.shape, fieldModel.shape))

                      TypeMapping.toWrappedType(finalFieldModel).flatMap { memberT =>
                        TypeMapping.toWrappedTypeReadOnly(finalFieldModel).flatMap { memberRoT =>
                            if (required contains memberName) {
                              unwrapSdkValue(finalFieldModel, propertyNameTerm).flatMap { unwrappedGet =>
                                wrapSdkValue(finalFieldModel, Term.Apply(Term.Select(Term.Name("impl"), propertyNameTerm), List.empty)).flatMap { wrappedGet =>
                                  roToEditable(finalFieldModel, propertyNameTerm).map { toEditable =>
                                    ModelFieldFragments(
                                      paramDef = param"""$propertyNameTerm: $memberT""",
                                      getterCall = toEditable,
                                      getterInterface = q"""def ${propertyNameTerm}: $memberRoT""",
                                      getterImplementation = q"""override def $propertyNameTerm: $memberRoT = $wrappedGet""",
                                      applyToBuilder = builder => q"""$builder.$fluentSetter($unwrappedGet)"""
                                    )
                                  }
                                }
                              }
                            } else {
                              val get = Term.Apply(Term.Select(Term.Name("impl"), propertyNameTerm), List.empty)
                              val valueTerm = Term.Name("value")
                              wrapSdkValue(finalFieldModel, valueTerm).flatMap { wrappedGet =>
                                unwrapSdkValue(finalFieldModel, valueTerm).flatMap { unwrappedGet =>
                                roToEditable(finalFieldModel, valueTerm).map { toEditable =>
                                  ModelFieldFragments(
                                    paramDef = param"""$propertyNameTerm: scala.Option[$memberT] = None""",
                                    getterCall = q"""$propertyNameTerm.map(value => $toEditable)""",
                                    getterInterface = q"""def ${propertyNameTerm}: scala.Option[$memberRoT]""",
                                    getterImplementation = if (wrappedGet == valueTerm) {
                                      q"""override def $propertyNameTerm: scala.Option[$memberRoT] = scala.Option($get)"""
                                    } else {
                                      q"""override def $propertyNameTerm: scala.Option[$memberRoT] = scala.Option($get).map(value => $wrappedGet)"""
                                    },
                                    applyToBuilder = builder => q"""$builder.optionallyWith($propertyNameTerm.map(value => $unwrappedGet))(_.$fluentSetter)"""
                                  )
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                }
                createBuilderTerm = Term.Apply(Term.Select(awsShapeNameTerm, Term.Name("builder")), List.empty)
                builderChain = fields.foldLeft(createBuilderTerm) { case (term, fieldFragments) =>
                  fieldFragments.applyToBuilder(term)
                }
              } yield ModelWrapper(
                code = List(
                  q"""case class $shapeNameT(..${fields.map(_.paramDef)}) extends $shapeNameRoInit {
                        def buildAwsValue(): $awsShapeNameT = {
                          import $shapeNameTerm.zioAwsBuilderHelper.BuilderOps
                          $builderChain.build()
                        }
                      }""",
                  q"""object $shapeNameTerm {
                            private lazy val zioAwsBuilderHelper: io.github.vigoo.zioaws.core.BuilderHelper[$awsShapeNameT] = io.github.vigoo.zioaws.core.BuilderHelper.apply
                            trait $roT {
                              def editable: $shapeNameT = $shapeNameTerm(..${fields.map(_.getterCall)})
                              ..${fields.map(_.getterInterface)}
                            }

                            private class Wrapper(impl: $awsShapeNameT) extends $shapeNameRoInit {
                              ..${fields.map(_.getterImplementation)}
                            }

                            def wrap(impl: $awsShapeNameT): $roT = new Wrapper(impl)
                          }
                         """)
              )
            case ModelType.List =>
              for {
                itemModel <- modelMap.get(shape.getListMember.getShape)
                elemT <- TypeMapping.toWrappedType(itemModel)
              } yield ModelWrapper(
                code = List(q"""type $shapeNameT = List[$elemT]""")
              )
            case ModelType.Map =>
              for {
                keyModel <- modelMap.get(shape.getMapKeyType.getShape)
                valueModel <- modelMap.get(shape.getMapValueType.getShape)
                keyT <- TypeMapping.toWrappedType(keyModel)
                valueT <- TypeMapping.toWrappedType(valueModel)
              } yield ModelWrapper(
                code = List(q"""type $shapeNameT = Map[$keyT, $valueT]""")
              )
            case ModelType.Enum =>
              ZIO.access[GeneratorContext](_.get.modelPkg).flatMap { modelPkg =>
                val shapeNameI = Init(shapeNameT, Name.Anonymous(), List.empty)
                val enumValueList = "unknownToSdkVersion" :: applyEnumModifiers(models, m, shape.getEnumValues.asScala.toList)
                val enumValues = enumValueList.map { enumValue =>
                  val enumValueTerm = Term.Name(enumValue)
                  val enumValueScreaming = Term.Name(namingStrategy.getEnumValueName(enumValue))
                  q"""final case object $enumValueTerm extends $shapeNameI {
                            override def unwrap: $awsShapeNameT = ${Term.Select(modelPkg, Term.Name(shapeName))}.${enumValueScreaming}
                          }
                       """
                }
                val wrapPatterns =
                  Term.Match(Term.Name("value"),
                    enumValueList.map { enumValue =>
                      val enumValueTerm = Term.Name(enumValue)
                      val enumValueScreaming = Term.Name(namingStrategy.getEnumValueName(enumValue))
                      val term = Term.Select(Term.Select(modelPkg, Term.Name(shapeName)), enumValueScreaming)
                      p"""case $term => $enumValueTerm"""
                    })

                ZIO.succeed(ModelWrapper(
                  code = List(
                    q"""sealed trait $shapeNameT {
                              def unwrap: $awsShapeNameT
                         }""",
                    q"""object $shapeNameTerm {
                              def wrap(value: $awsShapeNameT): $shapeNameT =
                                $wrapPatterns

                              ..$enumValues
                            }
                         """
                  )
                ))
              }
            case ModelType.String =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = String""")
              ))
            case ModelType.Integer =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = Int""")
              ))
            case ModelType.Long =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = Long""")
              ))
            case ModelType.Float =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = Float""")
              ))
            case ModelType.Double =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = Double""")
              ))
            case ModelType.Boolean =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = Boolean""")
              ))
            case ModelType.Timestamp =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = Instant""")
              ))
            case ModelType.Blob =>
              ZIO.succeed(ModelWrapper(
                code = List(q"""type $shapeNameT = Chunk[Byte]""")
              ))
            case typ =>
              ZIO.succeed(ModelWrapper(List(q"""type $shapeNameT = Unit""")))
          }
        } yield wrapper
      }

      private def createGeneratorContext(id: ModelId, model: C2jModels): ZLayer[Any, Nothing, GeneratorContext] =
        ZLayer.succeed {
          val pkgName = Term.Name(id.moduleName)

          new GeneratorContext.Service {
            override val service: ModelId = id
            override val modelPkg: Term.Ref = getSdkModelPackage(id, pkgName)
            override val paginatorPkg: Term.Ref = getSdkPaginatorPackage(id, pkgName)
            override val namingStrategy: NamingStrategy = new DefaultNamingStrategy(model.serviceModel(), model.customizationConfig())
            override val modelMap: ModelMap = ModelCollector.collectUsedModels(namingStrategy, model)
            override val models: C2jModels = model
          }
        }

      private def generateServiceModelsCode(): ZIO[GeneratorContext, GeneratorFailure, String] = {
        getModels.flatMap { model =>
          getModelMap.flatMap { modelMap =>
            getService.flatMap { id =>
              val pkgName = Term.Name(id.moduleName)
              val fullPkgName = Term.Select(q"io.github.vigoo.zioaws", pkgName)

              val filteredModels = filterModels(model, modelMap.all)
              val (primitives, complexes) = filteredModels.partition(model => TypeMapping.isPrimitiveType(model.shape))

              val primitiveModels = removeDuplicates(primitives)
              val complexModels = removeDuplicates(complexes)

              val parentModuleImport =
                if (id.subModule.isDefined) {
                  val parentModelPackage = Import(List(Importer(Term.Select(Term.Select(q"io.github.vigoo.zioaws", Term.Name(id.name)), Term.Name("model")), List(Importee.Wildcard()))))
                  List(parentModelPackage)
                } else {
                  List.empty
                }

              for {
                primitiveModels <- ZIO.foreach(primitiveModels.toList)(generateModel)
                models <- ZIO.foreach(complexModels.toList)(generateModel)
              } yield
                q"""package $fullPkgName {

                          import scala.jdk.CollectionConverters._
                          import java.time.Instant
                          import zio.Chunk
                          import software.amazon.awssdk.core.SdkBytes

                          ..$parentModuleImport

                          package object model {
                            object primitives {
                              ..${primitiveModels.flatMap(_.code)}
                            }

                          ..${models.flatMap(_.code)}
                          }}""".toString

              }
          }
        }
      }

      private def generateServiceModule(): ZIO[GeneratorContext, GeneratorFailure, Unit] =
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
          _ <- ZIO(Files.write(moduleFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()

      private def generateServiceModels(): ZIO[GeneratorContext, GeneratorFailure, Unit] =
        for {
          code <- generateServiceModelsCode()
          id <- getService
          moduleName = id.moduleName
          moduleRoot = config.parameters.targetRoot.resolve(moduleName)
          scalaRoot = moduleRoot.resolve("src/main/scala")
          packageParent = scalaRoot.resolve("io/github/vigoo/zioaws")
          packageRoot = packageParent.resolve(moduleName)
          modelsRoot = packageRoot.resolve("model")
          moduleFile = modelsRoot.resolve("package.scala")
          _ <- ZIO(Files.createDirectories(modelsRoot)).mapError(FailedToCreateDirectories)
          _ <- ZIO(Files.write(moduleFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()

      override def generateServiceCode(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit] = {
        val generate = for {
          _ <- generateServiceModule()
          _ <- generateServiceModels()
        } yield ()

        generate.provideLayer(createGeneratorContext(id, model))
      }

      override def generateBuildSbt(ids: Set[ModelId]): ZIO[Console, GeneratorFailure, Unit] =
        for {
          code <- ZIO.succeed(generateBuildSbtCode(ids))
          buildFile = config.parameters.targetRoot.resolve("build.sbt")
          _ <- ZIO(Files.write(buildFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()

      override def copyCoreProject(): IO[GeneratorFailure, Unit] =
        for {
          _ <- ZIO {
            os.remove.all(
              os.Path(config.parameters.targetRoot.resolve("zio-aws-core")))
          }.mapError(FailedToDelete)
          targetSrc = config.parameters.targetRoot.resolve("zio-aws-core").resolve("src")
          _ <- ZIO(Files.createDirectories(targetSrc)).mapError(FailedToCreateDirectories)
          _ <- ZIO {
            os.copy(
              os.Path(config.parameters.sourceRoot.resolve("zio-aws-core").resolve("src")),
              os.Path(targetSrc),
              replaceExisting = true)
          }.mapError(FailedToCopy)
        } yield ()
    }
  }

  def generateServiceCode(id: ModelId, model: C2jModels): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateServiceCode(id, model))

  def generateBuildSbt(ids: Set[ModelId]): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateBuildSbt(ids))

  def copyCoreProject(): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.copyCoreProject())
}
