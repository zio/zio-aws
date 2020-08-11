package io.github.vigoo.zioaws.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.clipp.zioapi.config.ClippConfig
import io.github.vigoo.zioaws.codegen.loader.ModelId
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
      def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit]

      def generateServiceModels(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit]

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

      private def opRequestName(namingStrategy: NamingStrategy, opName: String): Type.Name =
        Type.Name(namingStrategy.getRequestClassName(opName))

      private def opResponseName(namingStrategy: NamingStrategy, opName: String): Type.Name =
        Type.Name(namingStrategy.getResponseClassName(opName))

      private def tryFindEventStreamShape(models: C2jModels, outputShape: String, alreadyChecked: Set[Shape] = Set.empty): Option[(String, Shape)] = {
        Option(models.serviceModel().getShape(outputShape)) match {
          case Some(shape) if !(alreadyChecked.contains(shape)) =>
            if (shape.isEventStream) {
              Some((outputShape, shape))
            } else {
              shape.getMembers
                .asScala
                .values
                .map(member => tryFindEventStreamShape(models, member.getShape, alreadyChecked + shape))
                .find(_.isDefined)
                .flatten
            }
          case _ =>
            None
        }
      }

      private def findEventStreamShape(models: C2jModels, outputShape: String): ZIO[GeneratorContext, GeneratorFailure, (String, Shape)] =
        tryFindEventStreamShape(models, outputShape) match {
          case Some(value) => ZIO.succeed(value)
          case None => ZIO.access[GeneratorContext](_.get.serviceName).flatMap(serviceName => ZIO.fail(CannotFindEventStreamInShape(serviceName, outputShape)))
        }

      private def safeTypeName(customizationConfig: CustomizationConfig, modelPkg: Term.Ref, typ: Type): Type =
        typ match {
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

      private def generateServiceMethods(namingStrategy: NamingStrategy,
                                         models: C2jModels,
                                         modelMap: ModelMap): ZIO[Console with GeneratorContext, GeneratorFailure, List[ServiceMethods]] = {
        val serviceNameT = Type.Name(namingStrategy.getServiceName)

        ZIO.foreach(OperationCollector.getFilteredOperations(models).toList) { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          ZIO.access[GeneratorContext](_.get.modelPkg).flatMap { modelPkg =>
            OperationCollector.get(models, modelMap, opName, op).flatMap {
              case RequestResponse(pagination) =>
                val raw = ServiceMethod(
                  interface =
                    q"""def $methodName(request: $requestName): IO[AwsError, $responseName]""",
                  implementation =
                    q"""def $methodName(request: $requestName): IO[AwsError, $responseName] =
                        asyncRequestResponse[$requestName, $responseName](api.$methodName)(request)""",
                  accessor =
                    q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, $responseName] =
                        ZIO.accessM(_.get.$methodName(request))"""
                )
                pagination match {
                  case Some(pagination) =>
                    val methodNameStream = Term.Name(methodName.value + "Stream")
                    val itemType = safeTypeName(models.customizationConfig(), modelPkg, pagination.itemType)
                    val paginatorMethodName = Term.Name(methodName.value + "Paginator")
                    val publisherType = Type.Name(opName + "Publisher")

                    val streamedPaginator = ServiceMethod(
                      interface =
                        q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $itemType]]""",
                      implementation =
                        q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $itemType]] =
                            asyncPaginatedRequest[$requestName, $itemType, $publisherType](api.$paginatorMethodName, _.${Term.Name(pagination.name.uncapitalize)}())(request)""",
                      accessor =
                        q"""def $methodNameStream(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $itemType]] =
                            ZIO.accessM(_.get.$methodNameStream(request))"""
                    )

                    ZIO.succeed(ServiceMethods(
                      raw,
                      streamedPaginator
                    ))
                  case None =>
                    ZIO.succeed(ServiceMethods(raw))
                }
              case StreamedInput =>
                ZIO.succeed(ServiceMethods(
                  ServiceMethod(
                    interface =
                      q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, $responseName]""",
                    implementation =
                      q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, $responseName] =
                      asyncRequestInputStream[$requestName, $responseName](api.$methodName)(request, body)""",
                    accessor =
                      q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, $responseName] =
                          ZIO.accessM(_.get.$methodName(request, body))"""
                  )))
              case StreamedOutput =>
                ZIO.succeed(ServiceMethods(
                  ServiceMethod(
                    interface =
                      q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseName]]""",
                    implementation =
                      q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseName]] =
                          asyncRequestOutputStream[$requestName, $responseName](api.$methodName[zio.Task[StreamingOutputResult[$responseName]]])(request)""",
                    accessor =
                      q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseName]] =
                          ZIO.accessM(_.get.$methodName(request))"""
                  )))
              case StreamedInputOutput =>
                ZIO.succeed(ServiceMethods(
                  ServiceMethod(
                    interface =
                      q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]]""",
                    implementation =
                      q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]] =
                          asyncRequestInputOutputStream[$requestName, $responseName](api.$methodName[zio.Task[StreamingOutputResult[$responseName]]])(request, body)""",
                    accessor =
                      q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, AwsError, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseName]] =
                          ZIO.accessM(_.get.$methodName(request, body))"""
                  )))
              case EventStreamInput =>
                findEventStreamShape(models, op.getInput.getShape).flatMap { case (eventStreamName, _) =>
                  val eventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(eventStreamName))
                  ZIO.succeed(ServiceMethods(
                    ServiceMethod(
                      interface =
                        q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $eventT]): IO[AwsError, $responseName]""",
                      implementation =
                        q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $eventT]): IO[AwsError, $responseName] =
                            asyncRequestEventInputStream[$requestName, $responseName, $eventT](api.$methodName)(requset, input)""",
                      accessor =
                        q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $eventT]): ZIO[$serviceNameT, AwsError, $responseName] =
                            ZIO.accessM(_.get.$methodName(request, input))"""
                    )))
                }
              case EventStreamOutput =>
                findEventStreamShape(models, op.getOutput.getShape).flatMap { case (eventStreamShapeName, eventStreamShape) =>
                  val eventStreamT = Type.Name(eventStreamShapeName)
                  val eventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(eventStreamShape.getMembers.asScala.keys.head))

                  val responseHandlerName = Term.Name(opName + "ResponseHandler")
                  val responseHandlerT = Type.Name(responseHandlerName.value)
                  val visitorInit = Init(Type.Select(responseHandlerName, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                  val visitor: Term.NewAnonymous =
                    q"""new $visitorInit {
                          override def visit(event: $eventT): Unit = runtime.unsafeRun(queue.offer(event))
                        }"""

                  ZIO.succeed(ServiceMethods(
                    ServiceMethod(
                      interface =
                        q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $eventT]]""",
                      implementation =
                        q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, AwsError, $eventT]] =
                          ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                            asyncRequestEventOutputStream[$requestName, $responseName, $responseHandlerT, $eventStreamT, $eventT](
                              (request: $requestName, handler: $responseHandlerT) => api.$methodName(request, handler),
                              (queue: zio.Queue[$eventT]) =>
                                $responseHandlerName.builder()
                                  .subscriber($visitor)
                                  .build()
                            )(request)
                          }
                        """,
                      accessor =
                        q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $eventT]] =
                            ZIO.accessM(_.get.$methodName(request))"""
                    )))
                }
              case EventStreamInputOutput =>
                findEventStreamShape(models, op.getInput.getShape).flatMap { case (inputEventStreamName, _) =>
                  val inEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(inputEventStreamName))

                  findEventStreamShape(models, op.getOutput.getShape).flatMap { case (outputEventStreamShapeName, outputEventStreamShape) =>
                    val outEventStreamT = Type.Name(outputEventStreamShapeName)
                    val outEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(outputEventStreamShape.getMembers.asScala.keys.head))
                    val responseHandlerName = Term.Name(opName + "ResponseHandler")
                    val responseHandlerT = Type.Name(responseHandlerName.value)
                    val visitorInit = Init(Type.Select(responseHandlerName, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                    val visitor: Term.NewAnonymous =
                      q"""new $visitorInit {
                          override def visit(event: $outEventT): Unit = runtime.unsafeRun(queue.offer(event))
                        }"""

                    ZIO.succeed(ServiceMethods(
                      ServiceMethod(
                        interface =
                          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, AwsError, $outEventT]]""",
                        implementation =
                          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, AwsError, $outEventT]] =
                                 ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                                   asyncRequestEventInputOutputStream[$requestName, $responseName, $inEventT, $responseHandlerT, $outEventStreamT, $outEventT](
                                     (request: $requestName, input: Publisher[$inEventT], handler: $responseHandlerT) => api.$methodName(request, input, handler),
                                     (queue: zio.Queue[$outEventT]) =>
                                       $responseHandlerName.builder()
                                         .subscriber($visitor)
                                         .build())(request, input)
                                 }""",
                        accessor =
                          q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, AwsError, $inEventT]): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, AwsError, $outEventT]] =
                            ZIO.accessM(_.get.$methodName(request, input))"""
                      )))
                  }
                }
            }
          }
        }
      }

      private def generateServiceModuleCode(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, String] = {
        val namingStrategy = new DefaultNamingStrategy(model.serviceModel(), model.customizationConfig())

        val pkgName = Term.Name(id.moduleName)


        val serviceName = Term.Name(namingStrategy.getServiceName)
        val serviceNameT = Type.Name(serviceName.value)
        val serviceTrait = Type.Select(serviceName, Type.Name("Service"))
        val clientInterface = Type.Name(serviceName.value + "AsyncClient")
        val clientInterfaceSingleton = Term.Name(serviceName.value + "AsyncClient")
        val clientInterfaceBuilder = Type.Name(serviceName.value + "AsyncClientBuilder")

        val modelPackage = getSdkModelPackage(id, pkgName)
        val modelMap = ModelCollector.collectUsedModels(namingStrategy, model)

        val generatorContext = ZLayer.succeed(new GeneratorContext.Service {
          override val serviceName: String = id.moduleName
          override val modelPkg: Term.Ref = modelPackage
        })

        generateServiceMethods(namingStrategy, model, modelMap)
          .flatMap { serviceMethods =>
            ZIO.foreach(model.serviceModel().getOperations.asScala.toList) { case (opName, op) =>
              OperationCollector.get(model, modelMap, opName, op).map {
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
                Some(Import(List(Importer(modelPackage, List(Importee.Wildcard()))))),
                if (hasPaginators) {
                  Some(id.subModuleName match {
                    case Some(submodule) if submodule != id.name =>
                      Import(List(Importer(q"software.amazon.awssdk.services.${Term.Name(id.name)}.${Term.Name(submodule)}.paginators", List(Importee.Wildcard()))))
                    case _ =>
                      Import(List(Importer(q"software.amazon.awssdk.services.$pkgName.paginators", List(Importee.Wildcard()))))
                  })
                } else None,
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
                Some(q"""import org.reactivestreams.Publisher""")
              ).flatten

              val module =
                q"""
              package object $pkgName {
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
                q"""
             package io.github.vigoo.zioaws {
               ..$imports
               $module
             }
             """
              pkg.toString
            }
          }.provideSomeLayer[Console](generatorContext)
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
                libraryDependencies += "software.amazon.awssdk" % $artifactStr % awsVersion
              ).dependsOn(..$deps)
           """
        }
        val awsVersionStr = Lit.String(VersionInfo.SDK_VERSION)
        val zioVersionStr = Lit.String(config.parameters.zioVersion)
        val zioReactiveStreamsInteropVersionStr = Lit.String(config.parameters.zioInteropReactiveStreamsVersion)

        val code =
          q"""
              val awsVersion = $awsVersionStr

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

      private def wrapSdkValue(model: Model, term: Term, modelMap: ModelMap): ZIO[GeneratorContext, GeneratorFailure, Term] =
        model.typ match {
          case ModelType.Map =>
            for {
              keyModel <- modelMap.get(model.shape.getMapKeyType.getShape)
              valueModel <- modelMap.get(model.shape.getMapValueType.getShape)
              key = Term.Name("key")
              value = Term.Name("value")
              wrapKey <- wrapSdkValue(keyModel, key, modelMap)
              wrapValue <- wrapSdkValue(valueModel, value, modelMap)
            } yield if (wrapKey == key && wrapValue == value) {
              q"""$term.asScala.toMap"""
            } else {
              q"""$term.asScala.map { case (key, value) => $wrapKey -> $wrapValue }.toMap"""
            }
          case ModelType.List =>
            for {
              valueModel <- modelMap.get(model.shape.getListMember.getShape)
              item = Term.Name("item")
              wrapItem <- wrapSdkValue(valueModel, item, modelMap)
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

      private def filterMembers(models: C2jModels, shape: String, members: List[(String, software.amazon.awssdk.codegen.model.service.Member)]): List[(String, software.amazon.awssdk.codegen.model.service.Member)] = {
        val shapeModifiers = Option(models.customizationConfig().getShapeModifiers).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
        val global = shapeModifiers.get("*")
        val local = shapeModifiers.get(shape)
        val globalExcludes = global.flatMap(g => Option(g.getExclude)).map(_.asScala).getOrElse(List.empty).map(_.toLowerCase)
        val localExcludes = local.flatMap(l => Option(l.getExclude)).map(_.asScala).getOrElse(List.empty).map(_.toLowerCase)

        members.filterNot { case (memberName, member) =>
          globalExcludes.contains(memberName.toLowerCase) ||
            localExcludes.contains(memberName.toLowerCase) ||
            member.isStreaming ||
            models.serviceModel().getShape(member.getShape).isStreaming
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

      private def generateModel(namingStrategy: NamingStrategy,
                                models: C2jModels,
                                modelMap: ModelMap,
                                m: Model): ZIO[Console with GeneratorContext, GeneratorFailure, ModelWrapper] = {
        val shapeName = m.name
        val shape = m.shape
        val shapeNameT = Type.Name(shapeName)
        val shapeNameTerm = Term.Name(shapeName)

        for {
          awsShapeNameT <- TypeMapping.toType(m, modelMap)
          wrapper <- m.typ match {
            case ModelType.Structure =>
              val roT = Type.Name("ReadOnly")
              val shapeNameRoT = Type.Select(shapeNameTerm, roT)
              val shapeNameRoInit = Init(shapeNameRoT, Name.Anonymous(), List.empty)

              val fieldList = filterMembers(models, m.shapeName, shape.getMembers.asScala.toList)
              val required = Option(shape.getRequired).map(_.asScala.toSet).getOrElse(Set.empty)

              for {
                fieldParams <- ZIO.foreach(fieldList) {
                  case (memberName, member) =>
                    modelMap.get(member.getShape).flatMap { fieldModel =>
                      val finalFieldModel = adjustFieldType(models, m, memberName, fieldModel)
                      val property = propertyName(namingStrategy, models, m, finalFieldModel, memberName)
                      val propertyNameTerm = Term.Name(property)

                      TypeMapping.toWrappedType(finalFieldModel, modelMap).map { memberT =>
                        if (required contains memberName) {
                          param"""$propertyNameTerm: $memberT"""
                        } else {
                          param"""$propertyNameTerm: scala.Option[$memberT] = None"""
                        }
                      }
                    }
                }
                fieldGetterInterfaces <- ZIO.foreach(fieldList) { case (memberName, member) =>
                  modelMap.get(member.getShape).flatMap { fieldModel =>
                    val finalFieldModel = adjustFieldType(models, m, memberName, fieldModel)
                    val property = propertyName(namingStrategy, models, m, finalFieldModel, memberName)
                    val propertyNameTerm = Term.Name(property)

                    TypeMapping.toWrappedTypeReadOnly(finalFieldModel, modelMap).map { memberT =>
                      if (required contains memberName) {
                        q"""def ${propertyNameTerm}: $memberT"""
                      } else {
                        q"""def ${propertyNameTerm}: scala.Option[$memberT]"""
                      }
                    }
                  }
                }
                fieldGetterImplementations <- ZIO.foreach(fieldList) { case (memberName, member) =>
                  modelMap.get(member.getShape).flatMap { fieldModel =>
                    val finalFieldModel = adjustFieldType(models, m, memberName, fieldModel)
                    val property = propertyName(namingStrategy, models, m, finalFieldModel, memberName)
                    val propertyNameTerm = Term.Name(property)
                    val propertyNameP = Pat.Var(propertyNameTerm)

                    TypeMapping.toWrappedTypeReadOnly(finalFieldModel, modelMap).flatMap { memberT =>
                      if (required contains memberName) {
                        wrapSdkValue(finalFieldModel, Term.Apply(Term.Select(Term.Name("impl"), propertyNameTerm), List.empty), modelMap).map { wrappedGet =>
                          q"""override val $propertyNameP: $memberT = $wrappedGet"""
                        }
                      } else {
                        val get = Term.Apply(Term.Select(Term.Name("impl"), propertyNameTerm), List.empty)
                        val valueTerm = Term.Name("value")
                        wrapSdkValue(finalFieldModel, valueTerm, modelMap).map { wrappedGet =>
                          if (wrappedGet == valueTerm) {
                            q"""override val $propertyNameP: scala.Option[$memberT] = scala.Option($get)"""
                          } else {
                            q"""override val $propertyNameP: scala.Option[$memberT] = scala.Option($get).map(value => $wrappedGet)"""
                          }
                        }
                      }
                    }
                  }
                }
              } yield ModelWrapper(
                code = List(
                  q"""case class $shapeNameT(..$fieldParams) extends $shapeNameRoInit {}""",
                  q"""object $shapeNameTerm {
                            trait $roT {
                              ..$fieldGetterInterfaces
                            }

                            private class Wrapper(impl: $awsShapeNameT) extends $shapeNameRoInit {
                              ..$fieldGetterImplementations
                            }

                            def wrap(impl: $awsShapeNameT): $roT = new Wrapper(impl)
                          }
                         """)
              )
            case ModelType.List =>
              for {
                itemModel <- modelMap.get(shape.getListMember.getShape)
                elemT <- TypeMapping.toWrappedType(itemModel, modelMap)
              } yield ModelWrapper(
                code = List(q"""type $shapeNameT = List[$elemT]""")
              )
            case ModelType.Map =>
              for {
                keyModel <- modelMap.get(shape.getMapKeyType.getShape)
                valueModel <- modelMap.get(shape.getMapValueType.getShape)
                keyT <- TypeMapping.toWrappedType(keyModel, modelMap)
                valueT <- TypeMapping.toWrappedType(valueModel, modelMap)
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
              console.putStrLnErr(s"Unknown shape type: $typ").as(ModelWrapper(List(q"""type $shapeNameT = Unit""")))
          }
        } yield wrapper
      }

      private def generateServiceModelsCode(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, String] = {
        val namingStrategy = new DefaultNamingStrategy(model.serviceModel(), model.customizationConfig())
        val pkgName = Term.Name(id.moduleName)
        val fullPkgName = Term.Select(q"io.github.vigoo.zioaws", pkgName)
        val modelPackage: Term.Ref = getSdkModelPackage(id, pkgName)

        val modelMap = ModelCollector.collectUsedModels(namingStrategy, model)

        val generatorContext = ZLayer.succeed(new GeneratorContext.Service {
          override val serviceName: String = id.moduleName
          override val modelPkg: Term.Ref = modelPackage
        })

        val filteredModels = filterModels(model, modelMap.all)
        val primitiveModels =
          filteredModels
            .filter(model => TypeMapping.isPrimitiveType(model.shape))
            .map(m => (m.name -> m)) // unifying by name, as some models have both 'Timestamp' and 'timestamp' for example
            .toMap
            .values
            .toSet
        val complexModels = filteredModels diff primitiveModels

        val parentModuleImport =
          if (id.subModule.isDefined) {
            val parentModelPackage = Import(List(Importer(Term.Select(Term.Select(q"io.github.vigoo.zioaws", Term.Name(id.name)), Term.Name("model")), List(Importee.Wildcard()))))
            List(parentModelPackage)
          } else {
            List.empty
          }

        for {
          primitiveModels <- ZIO.foreach(primitiveModels.toList) { m =>
            generateModel(namingStrategy, model, modelMap, m)
              .provideSomeLayer[Console](generatorContext)
          }
          models <- ZIO.foreach(complexModels.toList) { m =>
            generateModel(namingStrategy, model, modelMap, m)
              .provideSomeLayer[Console](generatorContext)
          }
        } yield
          q"""package $fullPkgName {

                    import scala.jdk.CollectionConverters._
                    import java.time.Instant
                    import zio.Chunk

                    ..$parentModuleImport

                    package object model {
                      object primitives {
                        ..${primitiveModels.flatMap(_.code)}
                      }

                    ..${models.flatMap(_.code)}
                    }}""".toString

      }

      override def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit] =
        for {
          code <- generateServiceModuleCode(id, model)
          moduleName = id.moduleName
          moduleRoot = config.parameters.targetRoot.resolve(moduleName)
          scalaRoot = moduleRoot.resolve("src/main/scala")
          packageParent = scalaRoot.resolve("io/github/vigoo/zioaws")
          packageRoot = packageParent.resolve(moduleName)
          moduleFile = packageRoot.resolve("package.scala")
          _ <- ZIO(Files.createDirectories(packageRoot)).mapError(FailedToCreateDirectories)
          _ <- ZIO(Files.write(moduleFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()

      override def generateServiceModels(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit] =
        for {
          code <- generateServiceModelsCode(id, model)
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
          _ <- ZIO {
            os.copy(
              os.Path(config.parameters.sourceRoot.resolve("zio-aws-core")),
              os.Path(config.parameters.targetRoot.resolve("zio-aws-core")),
              replaceExisting = true)
          }.mapError(FailedToCopy)
        } yield ()
    }
  }

  def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateServiceModule(id, model))

  def generateServiceModels(id: ModelId, model: C2jModels): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateServiceModels(id, model))

  def generateBuildSbt(ids: Set[ModelId]): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateBuildSbt(ids))

  def copyCoreProject(): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.copyCoreProject())

  type GeneratorContext = Has[GeneratorContext.Service]

  object GeneratorContext {

    // TODO: add model map and naming strategy
    trait Service {
      val serviceName: String
      val modelPkg: Term.Ref
    }

  }

}
