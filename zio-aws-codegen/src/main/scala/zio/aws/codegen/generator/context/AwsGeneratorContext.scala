package zio.aws.codegen.generator.context

import zio.aws.codegen.loader.ModuleId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.ZIO

import scala.jdk.CollectionConverters._
import io.github.vigoo.metagen.core.Package
import zio.aws.codegen.generator.{AwsGeneratorFailure, Model, ModelMap}

/** The per-service state that the generator code reads through the ZIO
  * environment while it generates one service client. `AwsGeneratorImpl` builds
  * one instance for each service module.
  */
trait AwsGeneratorContext {

  /** The module that is being generated. */
  val service: ModuleId

  /** The package of the generated zio-aws client (the `zio.aws` package
    * followed by the module name).
    */
  val pkg: Package

  /** The package of the AWS Java SDK model classes of the service. */
  val modelPkg: Package

  /** The package of the AWS Java SDK paginator classes of the service. */
  val paginatorPkg: Package

  /** The AWS SDK naming strategy, built from the service model and the
    * customization config of the service.
    */
  val namingStrategy: NamingStrategy

  /** The models that the generator collected for the service. */
  val modelMap: ModelMap

  /** The AWS SDK service model, customization config and paginator model of the
    * service.
    */
  val models: C2jModels

  /** The sbt logger that receives the generator's warnings. */
  val logger: _root_.sbt.Logger
}

/** Accessors that read the members of an [[AwsGeneratorContext]] from the ZIO
  * environment.
  */
object AwsGeneratorContext {

  /** Returns the module that is being generated. */
  def getService: ZIO[AwsGeneratorContext, Nothing, ModuleId] =
    ZIO.serviceWith(_.service)

  /** Returns `ModuleId.name` of the module being generated; for a sub-module
    * such as `dynamodb:dynamodbstreams` that is `dynamodb`.
    */
  def getServiceName: ZIO[AwsGeneratorContext, Nothing, String] =
    ZIO.serviceWith(_.service.name)

  /** Returns the package of the generated zio-aws client. */
  def getPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.serviceWith(_.pkg)

  /** Returns the package of the AWS Java SDK model classes. */
  def getModelPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.serviceWith(_.modelPkg)

  /** Returns the package of the AWS Java SDK paginator classes. */
  def getPaginatorPkg: ZIO[AwsGeneratorContext, Nothing, Package] =
    ZIO.serviceWith(_.paginatorPkg)

  /** Returns the AWS SDK naming strategy of the service. */
  def getNamingStrategy: ZIO[AwsGeneratorContext, Nothing, NamingStrategy] =
    ZIO.serviceWith(_.namingStrategy)

  /** Returns the models that the generator collected for the service. */
  def getModelMap: ZIO[AwsGeneratorContext, Nothing, ModelMap] =
    ZIO.serviceWith(_.modelMap)

  /** Returns the AWS SDK models (service model, customization config, paginator
    * model) of the service.
    */
  def getModels: ZIO[AwsGeneratorContext, Nothing, C2jModels] =
    ZIO.serviceWith(_.models)

  /** Looks up a collected model by its name in the service model. Fails with
    * `UnknownShapeReference` when the name is not in the model map.
    */
  def get(name: String): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Model] =
    ZIO.serviceWithZIO(_.modelMap.get(name))

  /** Returns the sbt logger. */
  def getLogger: ZIO[AwsGeneratorContext, Nothing, _root_.sbt.Logger] =
    ZIO.serviceWith(_.logger)

  /** Logs `message` at warn level through the sbt logger. */
  def logWarn(message: => String): ZIO[AwsGeneratorContext, Nothing, Unit] =
    getLogger.flatMap { logger => ZIO.succeed(logger.warn(message)) }

  /** Accessors for the raw AWS SDK service model, without the filtering that
    * the model map applies.
    */
  object awsModel {

    /** Returns every operation of the service model as a pair of operation name
      * and operation, including deprecated and excluded ones.
      */
    def getOperations
        : ZIO[AwsGeneratorContext, Nothing, List[(String, Operation)]] =
      ZIO.serviceWith(_.models.serviceModel().getOperations.asScala.toList)

    /** Returns the shape with the given name from the service model, or `None`
      * when the service model has no such shape.
      */
    def getShape(
        name: String
    ): ZIO[AwsGeneratorContext, Nothing, Option[Shape]] =
      ZIO.serviceWith(r => Option(r.models.serviceModel().getShape(name)))
  }
}
