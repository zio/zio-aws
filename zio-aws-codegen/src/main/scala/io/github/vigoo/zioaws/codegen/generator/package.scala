package io.github.vigoo.zioaws.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.clipp.zioapi.config.ClippConfig
import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.{DefaultNamingStrategy, NamingStrategy}
import software.amazon.awssdk.core.util.VersionInfo
import zio._
import zio.console.Console

import scala.jdk.CollectionConverters._

package object generator {
  type Generator = Has[Generator.Service]

  object Generator {

    trait Service {
      def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Console, GeneratorError, Unit]

      def generateBuildSbt(ids: Set[ModelId]): ZIO[Console, GeneratorError, Unit]

      def copyCoreProject(): ZIO[Console, GeneratorError, Unit]
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

      private def isExcluded(customizationConfig: CustomizationConfig, opName: String): Boolean =
        Option(customizationConfig.getOperationModifiers)
          .flatMap(_.asScala.get(opName))
          .exists(_.isExclude)

      private def getFilteredOperations(models: C2jModels): Map[String, Operation] =
        models.serviceModel().getOperations.asScala
          .toMap
          .filter { case (_, op) => !op.isDeprecated }
          .filter { case (opName, _) => !isExcluded(models.customizationConfig(), opName) }

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

      private def findEventStreamShape(models: C2jModels, outputShape: String): ZIO[Any, GeneratorError, (String, Shape)] = {
        ZIO.fromEither(tryFindEventStreamShape(models, outputShape).toRight(CannotFindEventStreamInShape(outputShape)))
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

      private def generateServiceMethods(namingStrategy: NamingStrategy, models: C2jModels, modelPkg: Term.Ref): ZIO[Console, GeneratorError, List[ServiceMethods]] = {
        val serviceNameT = Type.Name(namingStrategy.getServiceName)

        ZIO.foreach(getFilteredOperations(models).toList) { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          OperationMethodType.get(models, opName, op).flatMap {
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
                      q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, Throwable, $itemType]]""",
                    implementation =
                      q"""def $methodNameStream(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, Throwable, $itemType]] =
                            asyncPaginatedRequest[$requestName, $itemType, $publisherType](api.$paginatorMethodName, _.${Term.Name(pagination.name.uncapitalize)}())(request)""",
                    accessor =
                      q"""def $methodNameStream(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, Throwable, $itemType]] =
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
                    q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, $responseName]""",
                  implementation =
                    q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, $responseName] =
                      asyncRequestInputStream[$requestName, $responseName](api.$methodName)(request, body)""",
                  accessor =
                    q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, $responseName] =
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
                    q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]]""",
                  implementation =
                    q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]] =
                          asyncRequestInputOutputStream[$requestName, $responseName](api.$methodName[zio.Task[StreamingOutputResult[$responseName]]])(request, body)""",
                  accessor =
                    q"""def $methodName(request: $requestName, body: zio.stream.ZStream[Any, Throwable, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseName]] =
                          ZIO.accessM(_.get.$methodName(request, body))"""
                )))
            case EventStreamInput =>
              findEventStreamShape(models, op.getInput.getShape).flatMap { case (eventStreamName, _) =>
                val eventT = safeTypeName(models.customizationConfig(), modelPkg, Type.Name(eventStreamName))
                ZIO.succeed(ServiceMethods(
                  ServiceMethod(
                    interface =
                      q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $eventT]): IO[AwsError, $responseName]""",
                    implementation =
                      q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $eventT]): IO[AwsError, $responseName] =
                            asyncRequestEventInputStream[$requestName, $responseName, $eventT](api.$methodName)(requset, input)""",
                    accessor =
                      q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $eventT]): ZIO[$serviceNameT, AwsError, $responseName] =
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
                      q"""def $methodName(request: $requestName): IO[AwsError, zio.stream.ZStream[Any, Throwable, $eventT]]""",
                    implementation =
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
                        """,
                    accessor =
                      q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, Throwable, $eventT]] =
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
                        q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, Throwable, $outEventT]]""",
                      implementation =
                        q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $inEventT]): IO[AwsError, zio.stream.ZStream[Any, Throwable, $outEventT]] =
                                 ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                                   asyncRequestEventInputOutputStream[$requestName, $responseName, $inEventT, $responseHandlerT, $outEventStreamT, $outEventT](
                                     (request: $requestName, input: Publisher[$inEventT], handler: $responseHandlerT) => api.$methodName(request, input, handler),
                                     (queue: zio.Queue[$outEventT]) =>
                                       $responseHandlerName.builder()
                                         .subscriber($visitor)
                                         .build())(request, input)
                                 }""",
                      accessor =
                        q"""def $methodName(request: $requestName, input: zio.stream.ZStream[Any, Throwable, $inEventT]): ZIO[$serviceNameT, AwsError, zio.stream.ZStream[Any, Throwable, $outEventT]] =
                            ZIO.accessM(_.get.$methodName(request, input))"""
                    )))
                }
              }
          }
        }
      }

      private def generateServiceModuleCode(id: ModelId, model: C2jModels): ZIO[Console, GeneratorError, String] = {
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

        generateServiceMethods(namingStrategy, model, modelPkg).flatMap { serviceMethods =>
          ZIO.foreach(model.serviceModel().getOperations.asScala.toList) { case (opName, op) =>
            OperationMethodType.get(model, opName, op).map {
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
             package io.gihtub.vigoo.zioaws {
               ..$imports
               $module
             }
             """
            pkg.toString
          }
        }
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

      override def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Console, GeneratorError, Unit] =
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

      override def generateBuildSbt(ids: Set[ModelId]): ZIO[Console, GeneratorError, Unit] =
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

  def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Generator with Console, GeneratorError, Unit] =
    ZIO.accessM(_.get.generateServiceModule(id, model))

  def generateBuildSbt(ids: Set[ModelId]): ZIO[Generator with Console, GeneratorError, Unit] =
    ZIO.accessM(_.get.generateBuildSbt(ids))

  def copyCoreProject(): ZIO[Generator with Console, GeneratorError, Unit] =
    ZIO.accessM(_.get.copyCoreProject())
}
