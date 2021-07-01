package io.github.vigoo.zioaws.codegen

import java.io.File
import java.nio.charset.StandardCharsets

import io.circe.yaml.parser._
import io.circe.yaml.syntax._
import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.naming.{
  DefaultNamingStrategy,
  NamingStrategy
}
import zio._
import zio.blocking.Blocking
import zio.console.Console
import zio.nio.file.Files

package object generator {
  type Generator = Has[Generator.Service]

  object Generator {

    trait Service {
      def generateServiceCode(
          id: ModelId,
          model: C2jModels,
          sbtLogger: sbt.Logger
      ): ZIO[Console with Blocking, GeneratorFailure, Set[File]]
      def generateCiYaml(
          ids: Set[ModelId]
      ): ZIO[Console with Blocking, GeneratorFailure, Unit]
      def generateArtifactList(
          ids: Set[ModelId]
      ): ZIO[Console with Blocking, GeneratorFailure, Unit]
    }

  }

  val live: ZLayer[Has[Parameters], Nothing, Generator] = ZLayer.fromService {
    cfg =>
      new Generator.Service with GeneratorBase with ServiceInterfaceGenerator
      with ServiceModelGenerator with GithubActionsGenerator
      with ArtifactListGenerator with HasConfig {
        import scala.meta._

        val config: Parameters = cfg

        private def getSdkModelPackage(
            id: ModelId,
            pkgName: Term.Name
        ): Term.Ref = {
          val modelPkg: Term.Ref = id.subModuleName match {
            case Some(submodule) if submodule != id.name =>
              q"software.amazon.awssdk.services.${Term.Name(id.name)}.model"
            case _ =>
              q"software.amazon.awssdk.services.$pkgName.model"
          }
          modelPkg
        }

        private def getSdkPaginatorPackage(
            id: loader.ModelId,
            pkgName: Term.Name
        ): Term.Select = {
          id.subModuleName match {
            case Some(submodule) if submodule.nonEmpty =>
              q"software.amazon.awssdk.services.${Term.Name(id.name)}.${Term.Name(submodule)}.paginators"
            case _ =>
              q"software.amazon.awssdk.services.$pkgName.paginators"
          }
        }

        private def createGeneratorContext(
            id: ModelId,
            model: C2jModels,
            sbtLogger: sbt.Logger
        ): ZLayer[Any, Nothing, GeneratorContext] =
          ZLayer.succeed {
            val pkgName = Term.Name(id.moduleName)

            new GeneratorContext.Service {
              override val service: ModelId = id
              override val modelPkg: Term.Ref = getSdkModelPackage(id, pkgName)
              override val paginatorPkg: Term.Ref =
                getSdkPaginatorPackage(id, pkgName)
              override val namingStrategy: NamingStrategy =
                new DefaultNamingStrategy(
                  model.serviceModel(),
                  model.customizationConfig()
                )
              override val modelMap: ModelMap =
                ModelCollector.collectUsedModels(namingStrategy, model)
              override val models: C2jModels = model
              override val logger: sbt.Logger = sbtLogger
            }
          }

        override def generateServiceCode(
            id: ModelId,
            model: C2jModels,
            sbtLogger: sbt.Logger
        ): ZIO[Console with Blocking, GeneratorFailure, Set[File]] = {
          val generate = for {
            moduleFile <- generateServiceModule()
            modelFiles <- generateServiceModels()
          } yield Set(moduleFile) union modelFiles

          generate
            .provideSomeLayer[Blocking](createGeneratorContext(id, model, sbtLogger))
        }

        override def generateCiYaml(
            ids: Set[ModelId]
        ): ZIO[Console with Blocking, GeneratorFailure, Unit] = {
          val result =
            generateCiYaml(ids, config.parallelCiJobs, config.separateCiJobs)
          for {
            _ <- writeIfDifferent(config.ciTarget, result)
          } yield ()
        }

        override def generateArtifactList(
            ids: Set[ModelId]
        ): ZIO[Console with Blocking, GeneratorFailure, Unit] =
          for {
            _ <- writeIfDifferent(
              config.artifactListTarget,
              generateArtifactList(ids, config.version)
            )
          } yield ()

        override protected val scalaVersion: String = cfg.scalaVersion
      }
  }

  def generateServiceCode(
      id: ModelId,
      model: C2jModels,
      sbtLogger: sbt.Logger
  ): ZIO[Generator with Console with Blocking, GeneratorFailure, Set[File]] =
    ZIO.accessM(_.get.generateServiceCode(id, model, sbtLogger))

  def generateCiYaml(
      ids: Set[ModelId]
  ): ZIO[Generator with Console with Blocking, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateCiYaml(ids))

  def generateArtifactList(
      ids: Set[ModelId]
  ): ZIO[Generator with Console with Blocking, GeneratorFailure, Unit] =
    ZIO.accessM(_.get.generateArtifactList(ids))
}
