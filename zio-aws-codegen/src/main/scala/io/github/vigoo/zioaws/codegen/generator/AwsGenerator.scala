package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.metagen.core.{Generator, GeneratorFailure, Package}
import io.github.vigoo.zioaws.codegen.Parameters
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

import java.io.File

trait AwsGenerator {
  def generateServiceCode(
      id: ModelId,
      model: C2jModels,
      sbtLogger: sbt.Logger
  ): ZIO[Console with Blocking, GeneratorFailure[AwsGeneratorFailure], Set[
    File
  ]]

  def generateCiYaml(
      ids: Set[ModelId]
  ): ZIO[Console with Blocking, GeneratorFailure[AwsGeneratorFailure], Unit]

  def generateArtifactList(
      ids: Set[ModelId]
  ): ZIO[Console with Blocking, GeneratorFailure[AwsGeneratorFailure], Unit]
}

object AwsGenerator {

  val live: ZLayer[Has[Parameters], Nothing, Has[AwsGenerator]] =
    ZLayer.fromService { (cfg: Parameters) =>
      new AwsGenerator
        with GeneratorBase
        with ServiceInterfaceGenerator
        with ServiceModelGenerator
        with GithubActionsGenerator
        with ArtifactListGenerator
        with HasConfig {
        import scala.meta._

        val config: Parameters = cfg

        private def getSdkPackage(id: ModelId): Package =
          id.subModuleName match {
            case Some(submodule) if submodule != id.name =>
              Package(
                "software",
                "amazon",
                "awssdk",
                "services",
                id.name
              )
            case _ =>
              Package(
                "software",
                "amazon",
                "awssdk",
                "services",
                id.moduleName
              )
          }

        private def getSdkModelPackage(id: ModelId): Package =
          getSdkPackage(id) / "model"

        private def getTargetPackage(id: ModelId): Package =
          Packages.zioaws / id.moduleName

        private def getSdkPaginatorPackage(id: ModelId): Package =
          id.subModuleName match {
            case Some(submodule) if submodule.nonEmpty =>
              Package(
                "software",
                "amazon",
                "awssdk",
                "services",
                id.name,
                submodule,
                "paginators"
              )
            case _ =>
              Package(
                "software",
                "amazon",
                "awssdk",
                "services",
                id.moduleName,
                "paginators"
              )
          }

        private def createGeneratorContext(
            id: ModelId,
            model: C2jModels,
            sbtLogger: sbt.Logger
        ): ZLayer[Any, Nothing, AwsGeneratorContext] =
          ZLayer.succeed {
            new AwsGeneratorContext.Service {
              override val pkg: Package = getTargetPackage(id)
              override val service: ModelId = id
              override val modelPkg: Package = getSdkModelPackage(id)
              override val paginatorPkg: Package =
                getSdkPaginatorPackage(id)
              override val namingStrategy: NamingStrategy =
                new DefaultNamingStrategy(
                  model.serviceModel(),
                  model.customizationConfig()
                )
              override val modelMap: ModelMap =
                ModelCollector.collectUsedModels(
                  modelPkg,
                  pkg / "model",
                  namingStrategy,
                  model
                )
              override val models: C2jModels = model
              override val logger: sbt.Logger = sbtLogger
            }
          }

        override def generateServiceCode(
            id: ModelId,
            model: C2jModels,
            sbtLogger: sbt.Logger
        ): ZIO[Console with Blocking, GeneratorFailure[
          AwsGeneratorFailure
        ], Set[File]] = {
          val generate = for {
            moduleFiles <- generateServiceModule()
            modelFiles <- generateServiceModels()
          } yield moduleFiles union modelFiles

          generate
            .provideSomeLayer[Blocking](
              Generator.live ++ createGeneratorContext(id, model, sbtLogger)
            )
            .map(_.map(_.toFile))
        }

        override def generateCiYaml(
            ids: Set[ModelId]
        ): ZIO[Console with Blocking, GeneratorFailure[
          AwsGeneratorFailure
        ], Unit] =
          Generator
            .generateRawFile(config.ciTarget) {
              ZIO
                .effect(
                  generateCiYaml(
                    ids,
                    config.parallelCiJobs,
                    config.separateCiJobs
                  )
                )
                .mapError(UnknownError)
            }
            .provideSomeLayer[Blocking](
              Generator.live
            )
            .unit

        override def generateArtifactList(
            ids: Set[ModelId]
        ): ZIO[Console with Blocking, GeneratorFailure[
          AwsGeneratorFailure
        ], Unit] =
          Generator
            .generateRawFile(config.artifactListTarget) {
              ZIO
                .effect(generateArtifactList(ids, config.version))
                .mapError(UnknownError)
            }
            .provideSomeLayer[Blocking](
              Generator.live
            )
            .unit

        override protected val scalaVersion: String = config.scalaVersion
      }
    }

  def generateServiceCode(
      id: ModelId,
      model: C2jModels,
      sbtLogger: sbt.Logger
  ): ZIO[Has[AwsGenerator] with Console with Blocking, GeneratorFailure[
    AwsGeneratorFailure
  ], Set[
    File
  ]] =
    ZIO.accessM(_.get.generateServiceCode(id, model, sbtLogger))

  def generateCiYaml(
      ids: Set[ModelId]
  ): ZIO[Has[
    AwsGenerator
  ] with Console with Blocking, GeneratorFailure[AwsGeneratorFailure], Unit] =
    ZIO.accessM(_.get.generateCiYaml(ids))

  def generateArtifactList(
      ids: Set[ModelId]
  ): ZIO[Has[
    AwsGenerator
  ] with Console with Blocking, GeneratorFailure[AwsGeneratorFailure], Unit] =
    ZIO.accessM(_.get.generateArtifactList(ids))
}
