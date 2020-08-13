package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.naming.NamingStrategy
import zio.{Has, ZIO}

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
}