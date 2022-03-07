package zio.aws.codegen.generator.context

import zio.aws.codegen.loader.ModuleId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.ZIO

import scala.jdk.CollectionConverters._
import io.github.vigoo.metagen.core.Package
import zio.aws.codegen.generator.{AwsGeneratorFailure, Model, ModelMap}

trait AwsGeneratorContext {
  val service: ModuleId
  val pkg: Package
  val modelPkg: Package
  val paginatorPkg: Package

  val namingStrategy: NamingStrategy
  val modelMap: ModelMap
  val models: C2jModels

  val logger: sbt.Logger
}

object AwsGeneratorContext {
  def getService: ZIO[AwsGeneratorContext, Nothing, ModuleId] =
    ZIO.serviceWith(_.service)

  def getServiceName: ZIO[AwsGeneratorContext, Nothing, String] =
    ZIO.serviceWith(_.service.name)

  def getPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.serviceWith(_.pkg)

  def getModelPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.serviceWith(_.modelPkg)

  def getPaginatorPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.serviceWith(_.paginatorPkg)

  def getNamingStrategy: ZIO[AwsGeneratorContext, Nothing, NamingStrategy] =
    ZIO.serviceWith(_.namingStrategy)

  def getModelMap: ZIO[AwsGeneratorContext, Nothing, ModelMap] =
    ZIO.serviceWith(_.modelMap)

  def getModels: ZIO[AwsGeneratorContext, Nothing, C2jModels] =
    ZIO.serviceWith(_.models)

  def get(name: String): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Model] =
    ZIO.serviceWithZIO(_.modelMap.get(name))

  def getLogger: ZIO[AwsGeneratorContext, Nothing, sbt.Logger] =
    ZIO.serviceWith(_.logger)

  def logWarn(message: => String): ZIO[AwsGeneratorContext, Nothing, Unit] =
    getLogger.flatMap { logger => ZIO.succeed(logger.warn(message)) }

  object awsModel {
    def getOperations
        : ZIO[AwsGeneratorContext, Nothing, List[(String, Operation)]] =
      ZIO.serviceWith(_.models.serviceModel().getOperations.asScala.toList)

    def getShape(
        name: String
    ): ZIO[AwsGeneratorContext, Nothing, Option[Shape]] =
      ZIO.serviceWith(r => Option(r.models.serviceModel().getShape(name)))
  }
}
