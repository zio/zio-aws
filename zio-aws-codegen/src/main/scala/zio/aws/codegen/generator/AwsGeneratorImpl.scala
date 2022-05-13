package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.{Generator, GeneratorFailure, Package}
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.naming.{
  DefaultNamingStrategy,
  NamingStrategy
}
import zio._
import zio.aws.codegen.Parameters
import zio.aws.codegen.generator.context._
import zio.aws.codegen.loader.ModuleId

import java.io.File

case class AwsGeneratorImpl(cfg: Parameters)
    extends AwsGenerator
    with GeneratorBase
    with ServiceInterfaceGenerator
    with ServiceModelGenerator
    with GithubActionsGenerator
    with ArtifactListGenerator
    with HasConfig
    with Blacklists {

  val config: Parameters = cfg

  private def getSdkPackage(id: ModuleId): Package =
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

  private def getSdkModelPackage(id: ModuleId): Package =
    getSdkPackage(id) / "model"

  private def getTargetPackage(id: ModuleId): Package =
    Packages.zioaws / id.moduleName

  private def getSdkPaginatorPackage(id: ModuleId): Package =
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
      id: ModuleId,
      model: C2jModels,
      sbtLogger: sbt.Logger
  ): ZLayer[Any, Nothing, AwsGeneratorContext] =
    ZLayer.succeed {
      new context.AwsGeneratorContext {
        override val pkg: Package = getTargetPackage(id)
        override val service: ModuleId = id
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
      id: ModuleId,
      model: C2jModels,
      sbtLogger: sbt.Logger
  ): ZIO[Any, GeneratorFailure[
    AwsGeneratorFailure
  ], Set[File]] = {
    val generate = for {
      moduleFiles <- generateServiceModule()
      modelFiles <- generateServiceModels()
    } yield moduleFiles union modelFiles

    generate
      .provide(
        Generator.live,
        createGeneratorContext(id, model, sbtLogger)
      )
      .map(_.map(_.toFile))
  }

  override def generateCiYaml(
      ids: Set[ModuleId]
  ): ZIO[Any, GeneratorFailure[
    AwsGeneratorFailure
  ], Unit] =
    Generator
      .generateRawFile(config.ciTarget) {
        ZIO
          .attempt(
            generateCiYaml(
              ids,
              config.parallelCiJobs,
              config.separateCiJobs
            )
          )
          .mapError(UnknownError)
      }
      .provide(Generator.live)
      .unit

  override def generateArtifactList(
      ids: Set[ModuleId]
  ): ZIO[Any, GeneratorFailure[
    AwsGeneratorFailure
  ], Unit] =
    Generator
      .generateRawFile(config.artifactListTarget) {
        ZIO
          .attempt(generateArtifactList(ids, config.version))
          .mapError(UnknownError)
      }
      .provide(Generator.live)
      .unit

  override protected val scalaVersion: String = config.scalaVersion
}
