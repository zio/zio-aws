package zio.aws.codegen.generator

import zio.aws.codegen.loader.ModelId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.{Has, ZIO}

import scala.jdk.CollectionConverters._

import io.github.vigoo.metagen.core.Package

package object context {
  type AwsGeneratorContext = Has[AwsGeneratorContext.Service]

  object AwsGeneratorContext {
    trait Service {
      val service: ModelId
      val pkg: Package
      val modelPkg: Package
      val paginatorPkg: Package

      val namingStrategy: NamingStrategy
      val modelMap: ModelMap
      val models: C2jModels

      val logger: sbt.Logger
    }
  }

  def getService: ZIO[AwsGeneratorContext, Nothing, ModelId] =
    ZIO.access(_.get.service)
  def getServiceName: ZIO[AwsGeneratorContext, Nothing, String] =
    ZIO.access(_.get.service.name)
  def getPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.access(_.get.pkg)
  def getModelPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.access(_.get.modelPkg)
  def getPaginatorPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.access(_.get.paginatorPkg)
  def getNamingStrategy: ZIO[AwsGeneratorContext, Nothing, NamingStrategy] =
    ZIO.access(_.get.namingStrategy)
  def getModelMap: ZIO[AwsGeneratorContext, Nothing, ModelMap] =
    ZIO.access(_.get.modelMap)
  def getModels: ZIO[AwsGeneratorContext, Nothing, C2jModels] =
    ZIO.access(_.get.models)
  def get(name: String): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Model] =
    ZIO.accessM(_.get.modelMap.get(name))
  def getLogger: ZIO[AwsGeneratorContext, Nothing, sbt.Logger] =
    ZIO.access(_.get.logger)

  def logWarn(message: => String): ZIO[AwsGeneratorContext, Nothing, Unit] =
    getLogger.flatMap { logger => ZIO.effectTotal(logger.warn(message)) }

  object awsModel {
    def getOperations
        : ZIO[AwsGeneratorContext, Nothing, List[(String, Operation)]] =
      ZIO.access(_.get.models.serviceModel().getOperations.asScala.toList)

    def getShape(
        name: String
    ): ZIO[AwsGeneratorContext, Nothing, Option[Shape]] =
      ZIO.access(r => Option(r.get.models.serviceModel().getShape(name)))
  }
}
