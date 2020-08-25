package io.github.vigoo.zioaws.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.clipp.zioapi.config.ClippConfig
import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.naming.{DefaultNamingStrategy, NamingStrategy}
import zio._
import zio.console.Console

package object generator {
  type Generator = Has[Generator.Service]

  object Generator {

    trait Service {
      def generateServiceCode(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit]

      def generateSubprojectsSbt(ids: Set[ModelId]): ZIO[Console, GeneratorFailure, Unit]
    }

  }

  val live: ZLayer[ClippConfig[Parameters], Nothing, Generator] = ZLayer.fromService { cfg =>
    new Generator.Service with GeneratorBase with ServiceInterfaceGenerator with ServiceModelGenerator with SbtGenerator with HasConfig {
      import scala.meta._

      val config: ClippConfig.Service[Parameters] = cfg

      private def getSdkModelPackage(id: ModelId, pkgName: Term.Name): Term.Ref = {
        val modelPkg: Term.Ref = id.subModuleName match {
          case Some(submodule) if submodule != id.name =>
            q"software.amazon.awssdk.services.${Term.Name(id.name)}.model"
          case _ =>
            q"software.amazon.awssdk.services.$pkgName.model"
        }
        modelPkg
      }

      private def getSdkPaginatorPackage(id: loader.ModelId, pkgName: Term.Name): Term.Select = {
        id.subModuleName match {
          case Some(submodule) if submodule.nonEmpty =>
            q"software.amazon.awssdk.services.${Term.Name(id.name)}.${Term.Name(submodule)}.paginators"
          case _ =>
            q"software.amazon.awssdk.services.$pkgName.paginators"
        }
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

      override def generateServiceCode(id: ModelId, model: C2jModels): ZIO[Console, GeneratorFailure, Unit] = {
        val generate = for {
          _ <- generateServiceModule()
          _ <- generateServiceModels()
        } yield ()

        generate.provideLayer(createGeneratorContext(id, model))
      }

      override def generateSubprojectsSbt(ids: Set[ModelId]): ZIO[Console, GeneratorFailure, Unit] =
        for {
          code <- ZIO.succeed(generateSubprojectsSbtCode(ids))
          sbtFile = config.parameters.targetRoot.resolve("subprojects.sbt")
          _ <- console.putStrLn(s"Generating $sbtFile")
          _ <- ZIO(Files.write(sbtFile, code.getBytes(StandardCharsets.UTF_8))).mapError(FailedToWriteFile)
        } yield ()
    }
  }

  def generateServiceCode(id: ModelId, model: C2jModels): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateServiceCode(id, model))

  def generateSubprojectsSbt(ids: Set[ModelId]): ZIO[Generator with Console, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateSubprojectsSbt(ids))
}
