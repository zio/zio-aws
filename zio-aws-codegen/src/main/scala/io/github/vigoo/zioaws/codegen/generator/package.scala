package io.github.vigoo.zioaws.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.clipp.zioapi.config.{ClippConfig, parameters}
import io.github.vigoo.zioaws.codegen.generator.Generator._
import io.github.vigoo.zioaws.codegen.loader.ModelId

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.internal.Utils
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{Operation, Paginators, Shape}
import software.amazon.awssdk.codegen.naming.{DefaultNamingStrategy, NamingStrategy}
import software.amazon.awssdk.core.util.VersionInfo
import zio._

import scala.meta.Type

package object generator {
  type Generator = Has[Generator.Service]

  object Generator {

    sealed trait GeneratorError

    case class FailedToCreateDirectories(reason: Throwable) extends GeneratorError

    case class FailedToWriteFile(reason: Throwable) extends GeneratorError

    case class FailedToCopy(reason: Throwable) extends GeneratorError

    case class FailedToDelete(reason: Throwable) extends GeneratorError

    trait Service {
      def generateServiceModule(id: ModelId, model: C2jModels): IO[GeneratorError, Unit]

      def generateBuildSbt(ids: Set[ModelId]): IO[GeneratorError, Unit]

      def copyCoreProject(): IO[GeneratorError, Unit]
    }

  }

  private implicit class StringOps(val value: String) extends AnyVal {
    def toCamelCase: String =
      (value.toList match {
        case ::(head, next) => head.toLower :: next
        case Nil => Nil
      }).mkString

    def uncapitalize: String = Utils.unCapitalize(value)
  }


  case class PaginationDefinition(name: String, itemType: Type)


  sealed trait OperationMethodType

  case class RequestResponse(pagination: Option[PaginationDefinition]) extends OperationMethodType

  case object StreamedInput extends OperationMethodType

  case object StreamedOutput extends OperationMethodType

  case object StreamedInputOutput extends OperationMethodType

  case object EventStreamInput extends OperationMethodType

  case object EventStreamOutput extends OperationMethodType

  case object EventStreamInputOutput extends OperationMethodType


  val live: ZLayer[ClippConfig[Parameters], Nothing, Generator] = ZLayer.fromService { config =>
    new Generator.Service {

      import scala.meta._

      private def opMethodName(opName: String): Term.Name =
        Term.Name(opName.toCamelCase)

      private def opRequestName(namingStrategy: NamingStrategy, opName: String): Type.Name =
        Type.Name(namingStrategy.getRequestClassName(opName))

      private def opResponseName(namingStrategy: NamingStrategy, opName: String): Type.Name =
        Type.Name(namingStrategy.getResponseClassName(opName))

      private def isExcluded(customizationConfig: CustomizationConfig, opName: String): Boolean =
        Option(customizationConfig.getOperationModifiers)
          .flatMap(_.asScala.get(opName))
          .exists(_.isExclude)

      private def getFilteredOperations(models: C2jModels): Map[String, Operation] =
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

      private def toType(name: String, models: C2jModels): Type = {
        val shape = models.serviceModel().getShape(name)
        shape.getType match {
          case "map" =>
            t"""java.util.Map[${toType(shape.getMapKeyType.getShape, models)}, ${toType(shape.getMapValueType.getShape, models)}]"""
          case "list" =>
            t"""java.util.List[${toType(shape.getListMember.getShape, models)}]"""
          case "string" =>
            t"String"
          case _ =>
            Type.Name(name)
        }
      }

      private def methodType(models: C2jModels, opName: String, op: Operation): OperationMethodType = {
        val inputIsStreaming = Option(op.getInput).flatMap(input => Option(models.serviceModel().getShape(input.getShape))).exists(hasStreamingMember(models, _))
        val outputIsStreaming = Option(op.getOutput).flatMap(output => Option(models.serviceModel().getShape(output.getShape))).exists(hasStreamingMember(models, _))

        val inputIsEventStream = Option(op.getInput).flatMap(input => Option(models.serviceModel().getShape(input.getShape))).exists(hasEventStreamMember(models, _))
        val outputIsEventStream = Option(op.getOutput).flatMap(output => Option(models.serviceModel().getShape(output.getShape))).exists(hasEventStreamMember(models, _))

        if (inputIsStreaming && outputIsStreaming) {
          StreamedInputOutput
        } else if (inputIsStreaming) {
          StreamedInput
        } else if (outputIsStreaming) {
          StreamedOutput
        } else if (inputIsEventStream && outputIsEventStream) {
          EventStreamInputOutput
        } else if (inputIsEventStream) {
          EventStreamInput
        } else if (outputIsEventStream) {
          EventStreamOutput
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
                          RequestResponse(pagination = Some(PaginationDefinition(
                            name = key,
                            itemType = toType(itemMember.getShape, models)
                          )))
                        case None =>
                          // TODO: log
                          RequestResponse(pagination = None)
                      }
                    case None =>
                      // TODO: log
                      RequestResponse(pagination = None)
                  }
                case None =>
                  RequestResponse(pagination = None)
              }
            case _ =>
              RequestResponse(pagination = None)
          }
        }
      }

      private def findEventStreamShape(models: C2jModels, outputShape: String, alreadyChecked: Set[Shape] = Set.empty): Option[(String, Shape)] = {
        Option(models.serviceModel().getShape(outputShape)) match {
          case Some(shape) if !(alreadyChecked.contains(shape)) =>
            if (shape.isEventStream) {
              Some((outputShape, shape))
            } else {
              shape.getMembers
                .asScala
                .values
                .map(member => findEventStreamShape(models, member.getShape, alreadyChecked + shape))
                .find(_.isDefined)
                .flatten
            }
          case _ =>
            None
        }
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

      private def generateServiceMethods(namingStrategy: NamingStrategy, models: C2jModels, modelPkg: Term.Ref): List[scala.meta.Member.Term with scala.meta.Stat] = {
        getFilteredOperations(models).toList.flatMap { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          methodType(models, opName, op) match {
            case RequestResponse(pagination) =>
              val raw = q"""def $methodName(request: $requestName): IO[AwsError, $responseName]"""
              pagination match {
                case Some(pagination) =>
                  val methodNameStream = Term.Name(methodName.value + "Stream")
                  val itemType = safeTypeName(models.customizationConfig(), modelPkg, pagination.itemType)
                  List(
                    raw,
                    q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, Throwable, $itemType]]"""
                  )
                case None =>
                  List(raw)
              }
            case StreamedInput =>
              List(q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, $responseName]""")
            case StreamedOutput =>
              List(q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseName]]""")
            case StreamedInputOutput =>
              List(q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]]""")
            case EventStreamInput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((eventStreamName, _)) =>
                  val eventT = Type.Name(eventStreamName)
                  List(q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, ${safeTypeName(models.customizationConfig(), modelPkg, eventT)}]): IO[AwsError, $responseName]""")
                case None =>
                  List(q"""def $methodName: String = "could not find event shape" """) // TODO: report error instead
              }
            case EventStreamOutput =>
              findEventStreamShape(models, op.getOutput.getShape) match {
                case Some((_, eventStreamShape)) =>
                  val eventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(eventStreamShape.getMembers.asScala.keys.head))

                  List(q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, Throwable, $eventT]]""")
                case None =>
                  List(q"""def $methodName: String = "could not find event shape" """) // TODO: report error instead
              }
            case EventStreamInputOutput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((inputEventStreamName, _)) =>
                  val inEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(inputEventStreamName))

                  findEventStreamShape(models, op.getOutput.getShape) match {
                    case Some((_, outputEventStreamShape)) =>
                      val outEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(outputEventStreamShape.getMembers.asScala.keys.head))

                      List(q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, Throwable, $outEventT]]""")
                    case None =>
                      List(q"""def $methodName: String = "could not find event shape" """) // TODO: report error instead
                  }
                case None =>
                  List(q"""def $methodName: String = "could not find event shape" """) // TODO: report error instead
              }
          }
        }
      }

      private def generateServiceMethodImplementations(namingStrategy: NamingStrategy, models: C2jModels, modelPkg: Term.Ref): List[Defn.Def] = {
        getFilteredOperations(models).flatMap { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          methodType(models, opName, op) match {
            case RequestResponse(pagination) =>
              val raw =
                q"""def $methodName(request: $requestName): IO[AwsError, $responseName] =
                      asyncRequestResponse[$requestName, $responseName](api.$methodName)(request)"""
              pagination match {
                case Some(pagination) =>
                  val methodNameStream = Term.Name(methodName.value + "Stream")
                  val paginatorMethodName = Term.Name(methodName.value + "Paginator")
                  val publisherType = Type.Name(opName + "Publisher")
                  val itemType = safeTypeName(models.customizationConfig(), modelPkg, pagination.itemType)

                  List(
                    raw,
                    q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, Throwable, $itemType]] =
                          asyncPaginatedRequest[$requestName, $itemType, $publisherType](api.$paginatorMethodName, _.${Term.Name(pagination.name.uncapitalize)}())(request)"""
                  )
                case None =>
                  List(raw)
              }
            case StreamedOutput =>
              List(
                q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseName]] =
                      asyncRequestOutputStream[$requestName, $responseName](api.$methodName[zio.Task[StreamingOutputResult[$responseName]]])(request)""")
            case StreamedInput =>
              List(
                q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, $responseName] =
                      asyncRequestInputStream[$requestName, $responseName](api.$methodName)(request, body)"""
              )
            case StreamedInputOutput =>
              List(
                q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]] =
                     asyncRequestInputOutputStream[$requestName, $responseName](api.$methodName[zio.Task[StreamingOutputResult[$responseName]]])(request, body)"""
              )
            case EventStreamInput =>
              findEventStreamShape(models, op.getInput.getShape).map {
                case (eventStreamName, _) =>
                  val eventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(eventStreamName))
                  q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $eventT]): IO[AwsError, $responseName] =
                        asyncRequestEventInputStream[$requestName, $responseName, $eventT](api.$methodName)(requset, input)"""
              }.toList
            case EventStreamOutput =>
              findEventStreamShape(models, op.getOutput.getShape).map {
                case (eventStreamShapeName, eventStreamShape) =>
                  val eventStreamT = Type.Name(eventStreamShapeName)
                  val eventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(eventStreamShape.getMembers.asScala.keys.head))
                  val responseHandlerName = Term.Name(opName + "ResponseHandler")
                  val responseHandlerT = Type.Name(responseHandlerName.value)
                  val visitorInit = Init(Type.Select(responseHandlerName, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                  val visitor: Term.NewAnonymous =
                    q"""new $visitorInit {
                          override def visit(event: $eventT): Unit = runtime.unsafeRun(queue.offer(event))
                        }"""

                  q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, Throwable, $eventT]] =
                          ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                            asyncRequestEventOutputStream[$requestName, $responseName, $responseHandlerT, $eventStreamT, $eventT](
                              (request: $requestName, handler: $responseHandlerT) => api.$methodName(request, handler),
                              (queue: zio.Queue[$eventT]) =>
                                $responseHandlerName.builder()
                                  .subscriber($visitor)
                                  .build()
                            )(request)
                          }
                        """
              }.toList
            case EventStreamInputOutput =>
              findEventStreamShape(models, op.getInput.getShape).flatMap {
                case (inputEventStreamName, _) =>
                  val inEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(inputEventStreamName))

                  findEventStreamShape(models, op.getOutput.getShape).map {
                    case (outputEventStreamShapeName, outputEventStreamShape) =>
                      val outEventStreamT = Type.Name(outputEventStreamShapeName)
                      val outEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(outputEventStreamShape.getMembers.asScala.keys.head))
                      val responseHandlerName = Term.Name(opName + "ResponseHandler")
                      val responseHandlerT = Type.Name(responseHandlerName.value)
                      val visitorInit = Init(Type.Select(responseHandlerName, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                      val visitor: Term.NewAnonymous =
                        q"""new $visitorInit {
                          override def visit(event: $outEventT): Unit = runtime.unsafeRun(queue.offer(event))
                        }"""

                      q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, Throwable, $outEventT]] =
                                 ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                                   asyncRequestEventInputOutputStream[$requestName, $responseName, $inEventT, $responseHandlerT, $outEventStreamT, $outEventT](
                                     (request: $requestName, input: Publisher[$inEventT], handler: $responseHandlerT) => api.$methodName(request, input, handler),
                                     (queue: zio.Queue[$outEventT]) =>
                                       $responseHandlerName.builder()
                                         .subscriber($visitor)
                                         .build())(request, input)
                                 }
                            """
                  }
              }.toList
          }
        }.toList
      }

      private def generateServiceAccessors(namingStrategy: NamingStrategy, models: C2jModels, modelPkg: Term.Ref): List[Defn.Def] = {
        val serviceNameT = Type.Name(namingStrategy.getServiceName)

        getFilteredOperations(models).flatMap { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          methodType(models, opName, op) match {
            case RequestResponse(pagination) =>
              val raw =
                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, $responseName] =
                      ZIO.accessM(_.get.$methodName(request))"""
              pagination match {
                case Some(pagination) =>
                  val methodNameStream = Term.Name(methodName.value + "Stream")
                  val itemType = safeTypeName(models.customizationConfig(), modelPkg, pagination.itemType)
                  List(
                    raw,
                    q"""def $methodNameStream(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, Throwable, $itemType]] =
                          ZIO.accessM(_.get.$methodNameStream(request))""")
                case None =>
                  List(raw)
              }
            case StreamedOutput =>
              List(
                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseName]] =
                      ZIO.accessM(_.get.$methodName(request))""")
            case StreamedInput =>
              List(
                q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, $responseName] =
                      ZIO.accessM(_.get.$methodName(request, body))""")
            case StreamedInputOutput =>
              List(
                q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseName]] =
                      ZIO.accessM(_.get.$methodName(request, body))"""
              )
            case EventStreamInput =>
              findEventStreamShape(models, op.getInput.getShape).map {
                case (eventStreamName, _) =>
                  val eventT = Type.Name(eventStreamName)
                  q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $eventT]): ZIO[$serviceNameT, AwsError, $responseName] =
                        ZIO.accessM(_.get.$methodName(request, input))"""
              }.toList
            case EventStreamOutput =>
              findEventStreamShape(models, op.getOutput.getShape).map {
                case (_, eventStreamShape) =>
                  val eventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(eventStreamShape.getMembers.asScala.keys.head))

                  q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, Throwable, $eventT]] =
                          ZIO.accessM(_.get.$methodName(request))"""
              }.toList
            case EventStreamInputOutput =>
              findEventStreamShape(models, op.getInput.getShape).flatMap {
                case (inputEventStreamName, _) =>
                  val inEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(inputEventStreamName))

                  findEventStreamShape(models, op.getOutput.getShape).map {
                    case (_, outputEventStreamShape) =>
                      val outEventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(outputEventStreamShape.getMembers.asScala.keys.head))

                      q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $inEventT]): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, Throwable, $outEventT]] =
                            ZIO.accessM(_.get.$methodName(request, input))"""
                  }
              }.toList
          }
        }.toList
      }

      private def generateServiceModuleCode(id: ModelId, model: C2jModels): String = {
        val namingStrategy = new DefaultNamingStrategy(model.serviceModel(), model.customizationConfig())

        val pkgName = Term.Name(id.moduleName)
        val submoduleName = id.subModule.flatMap { s =>
          val stripped = s.stripPrefix(id.name)
          if (stripped.isEmpty) None else Some(stripped)
        }

        val serviceName = Term.Name(namingStrategy.getServiceName)
        val serviceNameT = Type.Name(serviceName.value)
        val serviceTrait = Type.Select(serviceName, Type.Name("Service"))
        val clientInterface = Type.Name(serviceName.value + "AsyncClient")
        val clientInterfaceSingleton = Term.Name(serviceName.value + "AsyncClient")
        val clientInterfaceBuilder = Type.Name(serviceName.value + "AsyncClientBuilder")

        val modelPkg: Term.Ref = submoduleName match {
          case Some(submodule) if submodule != id.name =>
            q"software.amazon.awssdk.services.${Term.Name(id.name)}.model"
          case _ =>
            q"software.amazon.awssdk.services.$pkgName.model"
        }

        val serviceMethods = generateServiceMethods(namingStrategy, model, modelPkg)
        val serviceMethodImpls = generateServiceMethodImplementations(namingStrategy, model, modelPkg)
        val serviceAccessors = generateServiceAccessors(namingStrategy, model, modelPkg)

        val hasPaginators = model.serviceModel().getOperations.asScala.exists {
          case (opName, op) => methodType(model, opName, op) match {
            case RequestResponse(Some(pagination)) => true
            case _ => false
          }
        }

        val imports = List(
          Some(q"""import io.github.vigoo.zioaws.core._"""),
          Some(q"""import io.github.vigoo.zioaws.core.config.AwsConfig"""),
          Some(Import(List(Importer(modelPkg, List(Importee.Wildcard()))))),
          if (hasPaginators) {
            Some(submoduleName match {
              case Some(submodule) if submodule != id.name =>
                Import(List(Importer(q"software.amazon.awssdk.services.${Term.Name(id.name)}.${Term.Name(submodule)}.paginators", List(Importee.Wildcard()))))
              case _ =>
                Import(List(Importer(q"software.amazon.awssdk.services.$pkgName.paginators", List(Importee.Wildcard()))))
            })
          } else None,
          Some(submoduleName match {
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

                    ..$serviceMethods
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
             package io.gihtub.vigoo.zioaws {
               ..$imports
               $module
             }
             """
        pkg.toString
      }

      private def generateBuildSbtCode(ids: Set[ModelId]): String = {
        val projects = ids.toList.map { id =>
          val name = Term.Name(id.moduleName)
          val nameStr = Lit.String(id.moduleName)
          val artifactStr = Lit.String(id.name)
          val nameP = Pat.Var(name)

          q"""lazy val $nameP = Project($nameStr, file($nameStr)).settings(commonSettings).settings(
                libraryDependencies += "software.amazon.awssdk" % $artifactStr % awsVersion
              ).dependsOn(core)
           """
        }
        val awsVersionStr = Lit.String(VersionInfo.SDK_VERSION)
        val zioVersionStr = Lit.String(config.parameters.zioVersion)
        val zioReactiveStreamsInteropVersionStr = Lit.String(config.parameters.zioInteropReactiveStreamsVersion)
        val versionStr = Lit.String(config.parameters.version)

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

      override def generateServiceModule(id: ModelId, model: C2jModels): IO[Generator.GeneratorError, Unit] =
        for {
          code <- ZIO.succeed(generateServiceModuleCode(id, model))
          moduleName = id.moduleName
          moduleRoot = config.parameters.targetRoot.resolve(moduleName)
          scalaRoot = moduleRoot.resolve("src/main/scala")
          packageParent = scalaRoot.resolve("io/github/vigoo/zioaws")
          packageRoot = packageParent.resolve(moduleName)
          moduleFile = packageRoot.resolve("package.scala")
          _ <- ZIO(Files.createDirectories(packageRoot)).mapError(FailedToCreateDirectories)
          _ <- ZIO(Files.write(moduleFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()

      override def generateBuildSbt(ids: Set[ModelId]): IO[GeneratorError, Unit] =
        for {
          code <- ZIO.succeed(generateBuildSbtCode(ids))
          buildFile = config.parameters.targetRoot.resolve("build.sbt")
          _ <- ZIO(Files.write(buildFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()

      override def copyCoreProject(): IO[GeneratorError, Unit] =
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

  def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Generator, GeneratorError, Unit] =
    ZIO.accessM(_.get.generateServiceModule(id, model))

  def generateBuildSbt(ids: Set[ModelId]): ZIO[Generator, GeneratorError, Unit] =
    ZIO.accessM(_.get.generateBuildSbt(ids))

  def copyCoreProject(): ZIO[Generator, GeneratorError, Unit] =
    ZIO.accessM(_.get.copyCoreProject())
}
