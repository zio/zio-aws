package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.model.service.{Operation, Shape}
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.{Has, ZIO}

import scala.jdk.CollectionConverters._
import scala.meta.Term

package object context {
  type GeneratorContext = Has[GeneratorContext.Service]

  object GeneratorContext {
    trait Service {
      val service: ModelId
      val modelPkg: Term.Ref
      val paginatorPkg: Term.Ref

      val namingStrategy: NamingStrategy
      val modelMap: ModelMap
      val models: C2jModels
    }
  }

  def getService: ZIO[GeneratorContext, Nothing, ModelId] = ZIO.access(_.get.service)
  def getServiceName: ZIO[GeneratorContext, Nothing, String] = ZIO.access(_.get.service.name)
  def getModelPkg: ZIO[GeneratorContext, Nothing, Term.Ref] = ZIO.access(_.get.modelPkg)
  def getPaginatorPkg: ZIO[GeneratorContext, Nothing, Term.Ref] = ZIO.access(_.get.paginatorPkg)
  def getNamingStrategy: ZIO[GeneratorContext, Nothing, NamingStrategy] = ZIO.access(_.get.namingStrategy)
  def getModelMap: ZIO[GeneratorContext, Nothing, ModelMap] = ZIO.access(_.get.modelMap)
  def getModels: ZIO[GeneratorContext, Nothing, C2jModels] = ZIO.access(_.get.models)
  def get(name: String): ZIO[GeneratorContext, GeneratorFailure, Model] = ZIO.accessM(_.get.modelMap.get(name))

  object awsModel {
    def getOperations: ZIO[GeneratorContext, Nothing, List[(String, Operation)]] =
      ZIO.access(_.get.models.serviceModel().getOperations.asScala.toList)

    def getShape(name: String): ZIO[GeneratorContext, Nothing, Option[Shape]] =
      ZIO.access(r => Option(r.get.models.serviceModel().getShape(name)))
  }
}