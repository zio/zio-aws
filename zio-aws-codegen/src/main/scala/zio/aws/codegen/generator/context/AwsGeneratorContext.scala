package zio.aws.codegen.generator.context

import io.github.vigoo.metagen.core.Package
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.ZIO
import zio.aws.codegen.generator.{AwsGeneratorFailure, Model, ModelMap}
import zio.aws.codegen.loader.ModuleId

import scala.jdk.CollectionConverters.*

final case class AwsGeneratorContext(
    service: ModuleId,
    pkg: Package,
    modelPkg: Package,
    paginatorPkg: Package,
    namingStrategy: NamingStrategy,
    modelMap: ModelMap,
    models: C2jModels,
    logger: sbt.Logger
) {
  def get(name: String): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Model] =
    modelMap.get(name)

  def logWarn(message: => String): zio.UIO[Unit] =
    ZIO.succeed(logger.warn(message))

  def logInfo(message: => String): zio.UIO[Unit] =
    ZIO.succeed(logger.info(message))

  def serviceName: String = service.name

  def getOperations: List[(String, Operation)] =
    models.serviceModel().getOperations.asScala.toList

  def getShape(name: String): Option[Shape] = Option(
    models.serviceModel().getShape(name)
  )
}
