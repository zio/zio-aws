package io.github.vigoo.zioaws.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.clipp.zioapi.config.ClippConfig
import io.github.vigoo.zioaws.codegen.generator.Generator._
import io.github.vigoo.zioaws.codegen.loader.ModelId
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.{DefaultNamingStrategy, NamingStrategy}
import software.amazon.awssdk.core.util.VersionInfo
import zio._

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
  }

  sealed trait OperationMethodType

  case object RequestResponse extends OperationMethodType

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

      private def isExcluded(customizationConfig: Option[CustomizationConfig], opName: String): Boolean =
        customizationConfig
          .flatMap(c => Option(c.getOperationModifiers))
          .flatMap(_.asScala.get(opName))
          .exists(_.isExclude)

      private def getFilteredOperations(models: C2jModels): Map[String, Operation] =
        models.serviceModel().getOperations.asScala
          .toMap
          .filter { case (_, op) => !op.isDeprecated }
          .filter { case (opName, _) => !isExcluded(Option(models.customizationConfig()), opName) }

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

      private def methodType(models: C2jModels, op: Operation): OperationMethodType = {
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
          RequestResponse
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

      private def generateServiceMethods(namingStrategy: NamingStrategy, models: C2jModels): List[scala.meta.Member.Term with scala.meta.Stat] = {
        getFilteredOperations(models).map { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          methodType(models, op) match {
            case RequestResponse =>
              q"""def $methodName(request: $requestName): IO[AwsError, $responseName]"""
            case StreamedInput =>
              q"""def $methodName(request: $requestName, body: ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, $responseName]"""
            case StreamedOutput =>
              q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseName]]"""
            case StreamedInputOutput =>
              q"""def $methodName(request: $requestName, body: ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]]"""
            case EventStreamInput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((eventStreamName, _)) =>
                  val eventT = Type.Name(eventStreamName)
                  q"""def $methodName(request: $requestName, input: ZStream[Any, Throwable, $eventT]): IO[AwsError, $responseName]"""
                case None =>
                  q"""def $methodName: String = "could not find event shape" """ // TODO: report error instead
              }
              q"""def $methodName(): String = ${opName + " has event stream input, not supported yet"} """
            case EventStreamOutput =>
              findEventStreamShape(models, op.getOutput.getShape) match {
                case Some((_, eventStreamShape)) =>
                  val eventT = Type.Name(eventStreamShape.getMembers().asScala.keys.head)

                  q"""def $methodName(request: $requestName): IO[AwsError, ZStream[Any, Throwable, $eventT]]"""
                case None =>
                  q"""def $methodName: String = "could not find event shape" """ // TODO: report error instead
              }
            case EventStreamInputOutput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((inputEventStreamName, _)) =>
                  val inEventT = Type.Name(inputEventStreamName)

                  findEventStreamShape(models, op.getOutput.getShape) match {
                    case Some((_, outputEventStreamShape)) =>
                      val outEventT = Type.Name(outputEventStreamShape.getMembers().asScala.keys.head)

                      q"""def $methodName(request: $requestName, input: ZStream[Any, Throwable, $inEventT]): IO[AwsError, ZStream[Any, Throwable, $outEventT]]"""
                    case None =>
                      q"""def $methodName: String = "could not find event shape" """ // TODO: report error instead
                  }
                case None =>
                  q"""def $methodName: String = "could not find event shape" """ // TODO: report error instead
              }
          }
        }.toList
      }

      private def generateServiceMethodImplementations(namingStrategy: NamingStrategy, models: C2jModels): List[Defn.Def] = {
        getFilteredOperations(models).flatMap { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          methodType(models, op) match {
            case RequestResponse =>
              Some(
                q"""def $methodName(request: $requestName): IO[AwsError, $responseName] =
                      asyncRequestResponse[$requestName, $responseName](api.$methodName)(request)""")
            case StreamedOutput =>
              Some(
                q"""def $methodName(request: $requestName): IO[AwsError, StreamingOutputResult[$responseName]] =
                      asyncRequestOutputStream[$requestName, $responseName](api.$methodName[Task[StreamingOutputResult[$responseName]]])(request)""")
            case StreamedInput =>
              Some(
                q"""def $methodName(request: $requestName, body: ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, $responseName] =
                      asyncRequestInputStream[$requestName, $responseName](api.$methodName)(request, body)"""
              )
            case StreamedInputOutput =>
              Some(
                q"""def $methodName(request: $requestName, body: ZStream[Any, Throwable, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[$responseName]] =
                     asyncRequestInputOutputStream[$requestName, $responseName](api.$methodName[Task[StreamingOutputResult[$responseName]]])(request, body)"""
              )
            case EventStreamInput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((eventStreamName, _)) =>
                  val eventT = Type.Name(eventStreamName)
                  Some(q"""def $methodName(request: $requestName, input: ZStream[Any, Throwable, $eventT]): IO[AwsError, $responseName] =
                        asyncRequestEventInputStream[$requestName, $responseName, $eventT](api.$methodName)(requset, input)""")
                case None =>
                  None
              }
            case EventStreamOutput =>
              findEventStreamShape(models, op.getOutput.getShape) match {
                case Some((eventStreamShapeName, eventStreamShape)) =>
                  val eventStreamT = Type.Name(eventStreamShapeName)
                  val eventT = Type.Name(eventStreamShape.getMembers.asScala.keys.head)
                  val responseHandlerName = Term.Name(opName + "ResponseHandler")
                  val responseHandlerT = Type.Name(responseHandlerName.value)
                  val visitorInit = Init(Type.Select(responseHandlerName, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                  val visitor: Term.NewAnonymous =
                    q"""new $visitorInit {
                          override def visit(event: $eventT): Unit = runtime.unsafeRun(queue.offer(event))
                        }"""

                  Some(
                    q"""def $methodName(request: $requestName): IO[AwsError, ZStream[Any, Throwable, $eventT]] =
                          ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                            asyncRequestEventOutputStream[$requestName, $responseName, $responseHandlerT, $eventStreamT, $eventT](
                              (request: $requestName, handler: $responseHandlerT) => api.$methodName(request, handler),
                              (queue: Queue[$eventT]) =>
                                $responseHandlerName.builder()
                                  .subscriber($visitor)
                                  .build()
                            )(request)
                          }
                        """)
                case None =>
                  None
              }
            case EventStreamInputOutput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((inputEventStreamName, _)) =>
                  val inEventT = Type.Name(inputEventStreamName)

                  findEventStreamShape(models, op.getOutput.getShape) match {
                    case Some((outputEventStreamShapeName, outputEventStreamShape)) =>
                      val outEventStreamT = Type.Name(outputEventStreamShapeName)
                      val outEventT = Type.Name(outputEventStreamShape.getMembers.asScala.keys.head)
                      val responseHandlerName = Term.Name(opName + "ResponseHandler")
                      val responseHandlerT = Type.Name(responseHandlerName.value)
                      val visitorInit = Init(Type.Select(responseHandlerName, Type.Name("Visitor")), Name.Anonymous(), List.empty)

                      val visitor: Term.NewAnonymous =
                        q"""new $visitorInit {
                          override def visit(event: $outEventT): Unit = runtime.unsafeRun(queue.offer(event))
                        }"""

                      Some(q"""def $methodName(request: $requestName, input: ZStream[Any, Throwable, $inEventT]): IO[AwsError, ZStream[Any, Throwable, $outEventT]] =
                                 ZIO.runtime.flatMap { runtime: zio.Runtime[Any] =>
                                   asyncRequestEventInputOutputStream[$requestName, $responseName, $inEventT, $responseHandlerT, $outEventStreamT, $outEventT](
                                     (request: $requestName, input: Publisher[$inEventT], handler: $responseHandlerT) => api.$methodName(request, input, handler),
                                     (queue: Queue[$outEventT]) =>
                                       $responseHandlerName.builder()
                                         .subscriber($visitor)
                                         .build())(request, input)
                                 }
                            """)
                    case None =>
                      None
                  }
                case None =>
                  None
              }
          }
        }.toList
      }

      private def generateServiceAccessors(namingStrategy: NamingStrategy, models: C2jModels): List[Defn.Def] = {
        val serviceNameT = Type.Name(namingStrategy.getServiceName)

        getFilteredOperations(models).flatMap { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          methodType(models, op) match {
            case RequestResponse =>
              Some(
                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, $responseName] =
                      ZIO.accessM(_.get.$methodName(request))""")
            case StreamedOutput =>
              Some(
                q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseName]] =
                      ZIO.accessM(_.get.$methodName(request))""")
            case StreamedInput =>
              Some(
                q"""def $methodName(request: $requestName, body: ZStream[Any, Throwable, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, $responseName] =
                      ZIO.accessM(_.get.$methodName(request, body))""")
            case StreamedInputOutput =>
              Some(
                q"""def $methodName(request: $requestName, body: ZStream[Any, Throwable, Chunk[Byte]]): ZIO[$serviceNameT, AwsError, StreamingOutputResult[$responseName]] =
                      ZIO.accessM(_.get.$methodName(request, body))"""
              )
            case EventStreamInput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((eventStreamName, _)) =>
                  val eventT = Type.Name(eventStreamName)
                  Some(q"""def $methodName(request: $requestName, input: ZStream[Any, Throwable, $eventT]): ZIO[$serviceNameT, AwsError, $responseName] =
                        ZIO.accessM(_.get.$methodName(request, input))""")
                case None =>
                  None
              }
            case EventStreamOutput =>
              findEventStreamShape(models, op.getOutput.getShape) match {
                case Some((_, eventStreamShape)) =>
                  val eventT = Type.Name(eventStreamShape.getMembers.asScala.keys.head)

                  Some(
                    q"""def $methodName(request: $requestName): ZIO[$serviceNameT, AwsError, ZStream[Any, Throwable, $eventT]] =
                          ZIO.accessM(_.get.$methodName(request))""")
                case None =>
                  None
              }
            case EventStreamInputOutput =>
              findEventStreamShape(models, op.getInput.getShape) match {
                case Some((inputEventStreamName, _)) =>
                  val inEventT = Type.Name(inputEventStreamName)

                  findEventStreamShape(models, op.getOutput.getShape) match {
                    case Some((_, outputEventStreamShape)) =>
                      val outEventT = Type.Name(outputEventStreamShape.getMembers.asScala.keys.head)

                      Some(q"""def $methodName(request: $requestName, input: ZStream[Any, Throwable, $inEventT]): ZIO[$serviceNameT, AwsError, ZStream[Any, Throwable, $outEventT]] =
                            ZIO.accessM(_.get.$methodName(request, input))""")
                    case None =>
                      None
                  }
                case None =>
                  None
              }
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

        val serviceMethods = generateServiceMethods(namingStrategy, model)
        val serviceMethodImpls = generateServiceMethodImplementations(namingStrategy, model)
        val serviceAccessors = generateServiceAccessors(namingStrategy, model)

        val imports = List(
          q"""import io.github.vigoo.zioaws.core._""",
          q"""import io.github.vigoo.zioaws.core.config.AwsConfig""",
          submoduleName match {
            case Some(submodule) if submodule != id.name =>
              Import(List(Importer(q"software.amazon.awssdk.services.${Term.Name(id.name)}.model", List(Importee.Wildcard()))))
            case _ =>
              Import(List(Importer(q"software.amazon.awssdk.services.$pkgName.model", List(Importee.Wildcard()))))
          },
          submoduleName match {
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
          },
          q"""import zio._""",
          q"""import zio.stream._""",
          q"""import org.reactivestreams.Publisher"""
        )

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
        val versionStr = Lit.String(VersionInfo.SDK_VERSION)
        val zioVersionStr = Lit.String("1.0.0") // TODO: sync with build.sbt
        val zioReactiveStreamsInteropVersionStr = Lit.String("1.0.3.5") // TODO: sync with build.sbt

        val code =
          q"""
              val awsVersion = $versionStr

              lazy val commonSettings = Seq(
                scalaVersion := "2.13.3",
                organization := "io.github.vigoo",
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
