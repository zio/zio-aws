package io.github.vigoo.zioaws.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, OpenOption, Paths}

import io.github.vigoo.zioaws.codegen.config.Config
import io.github.vigoo.zioaws.codegen.generator.Generator.{FailedToCreateDirectories, FailedToWriteFile, GeneratorError}
import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.{DefaultNamingStrategy, NamingStrategy}
import zio._

import scala.jdk.CollectionConverters.MapHasAsScala
import scala.meta.tokens.Token.{Comment, Ident}

package object generator {
  type Generator = Has[Generator.Service]

  object Generator {
    sealed trait GeneratorError
    case class FailedToCreateDirectories(reason: Throwable) extends GeneratorError
    case class FailedToWriteFile(reason: Throwable) extends GeneratorError

    trait Service {
      def generateServiceModule(id: ModelId, model: C2jModels): IO[GeneratorError, Unit]
    }
  }

  private implicit class StringOps(val value: String) extends AnyVal {
    def toCamelCase: String =
      (value.toList match {
        case ::(head, next) => head.toLower :: next
        case Nil =>Nil
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

  val live: ZLayer[Config, Nothing, Generator] = ZLayer.fromService { config =>
    new Generator.Service {
      import scala.meta._

      private def opMethodName(opName: String): Term.Name =
        Term.Name(opName.toCamelCase)
      private def opRequestName(namingStrategy: NamingStrategy, opName: String): Type.Name =
        Type.Name(namingStrategy.getRequestClassName(opName))
      private def opResponseName(namingStrategy: NamingStrategy, opName: String): Type.Name =
        Type.Name(namingStrategy.getResponseClassName(opName))

      private def getFilteredOperations(models: C2jModels): Map[String, Operation] =
        models.serviceModel().getOperations.asScala
          .toMap
          .filter { case (_, op) => !op.isDeprecated }

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

      private def generateServiceMethods(namingStrategy: NamingStrategy, models: C2jModels): List[scala.meta.Member.Term with scala.meta.Stat] = {
        getFilteredOperations(models).map { case (opName, op) =>
          val methodName = opMethodName(opName)
          val requestName = opRequestName(namingStrategy, opName)
          val responseName = opResponseName(namingStrategy, opName)

          methodType(models, op) match {
            case RequestResponse =>
              q"""def $methodName(request: $requestName): IO[AwsError, $responseName]"""
            case StreamedInput =>
              q"""def $methodName(): String = ${opName + " has streamed input, not supported yet"} """
            case StreamedOutput =>
              q"""def $methodName(): String = ${opName + " has streamed output, not supported yet"} """
            case StreamedInputOutput =>
              q"""def $methodName(): String = ${opName + " has streamed input and output, not supported yet"} """
            case EventStreamInput =>
              q"""def $methodName(): String = ${opName + " has event stream input, not supported yet"} """
            case EventStreamOutput =>
              q"""def $methodName(): String = ${opName + " has event stream output, not supported yet"} """
            case EventStreamInputOutput =>
              q"""def $methodName(): String = ${opName + " has event stream input and output, not supported yet"} """
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
              Some(q"""def $methodName(request: $requestName): IO[AwsError, $responseName] =
                         asyncRequestResponse[$requestName, $responseName](api.$methodName)(request)""")
            case _ =>
              None
          }
        }.toList
      }

      private def generateServiceModuleCode(id: ModelId, model: C2jModels): String = {
        val namingStrategy = new DefaultNamingStrategy(model.serviceModel(), model.customizationConfig())

        val pkgName = Term.Name(id.subModule.getOrElse(id.name))
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

      override def generateServiceModule(id: ModelId, model: C2jModels): IO[Generator.GeneratorError, Unit] =
        for {
          code <- ZIO.succeed(generateServiceModuleCode(id, model))
          moduleRoot = config.parameters.targetRoot.resolve(id.subModule.getOrElse(id.name))
          moduleFile = moduleRoot.resolve("package.scala")
          _ <- ZIO(Files.createDirectories(moduleRoot)).mapError(FailedToCreateDirectories)
          _ <- ZIO(Files.write(moduleFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()
    }
  }

  def generateServiceModule(id: ModelId, model: C2jModels): ZIO[Generator, GeneratorError, Unit] =
    ZIO.service[Generator.Service].flatMap(_.generateServiceModule(id, model))
}
