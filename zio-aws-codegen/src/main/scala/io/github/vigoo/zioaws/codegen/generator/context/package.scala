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

  def service: ZIO[GeneratorContext, Nothing, ModelId] = ZIO.access(_.get.service)
  def serviceName: ZIO[GeneratorContext, Nothing, String] = ZIO.access(_.get.service.name)
  def modelPkg: ZIO[GeneratorContext, Nothing, Term.Ref] = ZIO.access(_.get.modelPkg)
  def paginatorPkg: ZIO[GeneratorContext, Nothing, Term.Ref] = ZIO.access(_.get.paginatorPkg)
  def namingStrategy: ZIO[GeneratorContext, Nothing, NamingStrategy] = ZIO.access(_.get.namingStrategy)
  def modelMap: ZIO[GeneratorContext, Nothing, ModelMap] = ZIO.access(_.get.modelMap)
  def models: ZIO[GeneratorContext, Nothing, C2jModels] = ZIO.access(_.get.models)
}