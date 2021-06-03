package io.github.vigoo.zioaws.codegen.generator

import java.nio.charset.StandardCharsets

import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.generator.syntax._
import software.amazon.awssdk.codegen.model.config.customization.ShapeModifier
import zio.blocking
import zio.{Chunk, ZIO}
import zio.nio.file.Files
import zio.nio.core.file.Path

import scala.jdk.CollectionConverters._
import scala.meta._
import scala.meta.internal.prettyprinters.TreeSyntax

trait GeneratorBase {

  protected def unwrapSdkValue(
      model: Model,
      term: Term,
      forceToString: Boolean = false
  ): ZIO[GeneratorContext, GeneratorFailure, Term] =
    model.typ match {
      case ModelType.Map =>
        for {
          modelMap <- getModelMap
          keyModel <- get(model.shape.getMapKeyType.getShape)
          valueModel <- get(model.shape.getMapValueType.getShape)
          key = Term.Name("key")
          value = Term.Name("value")
          unwrapKey <- unwrapSdkValue(keyModel, key, forceToString = true)
          unwrapValue <- unwrapSdkValue(valueModel, value, forceToString = true)
        } yield
          if (unwrapKey == key && unwrapValue == value) {
            q"""$term.asJava"""
          } else {
            q"""$term.map { case (key, value) => $unwrapKey -> $unwrapValue }.asJava"""
          }
      case ModelType.List =>
        for {
          modelMap <- getModelMap
          valueModel <- get(model.shape.getListMember.getShape)
          item = Term.Name("item")
          unwrapItem <- unwrapSdkValue(valueModel, item, forceToString = true)
        } yield
          if (unwrapItem == item) {
            q"""$term.asJavaCollection"""
          } else {
            q"""$term.map { item => $unwrapItem }.asJavaCollection"""
          }
      case ModelType.Enum =>
        val nameTerm = Term.Name(model.name)
        if (forceToString) {
          ZIO.succeed(q"""$term.unwrap.toString""")
        } else {
          ZIO.succeed(q"""$term.unwrap""")
        }
      case ModelType.Blob =>
        ZIO.succeed(q"""SdkBytes.fromByteArrayUnsafe($term.toArray[Byte])""")
      case ModelType.Structure =>
        ZIO.succeed(q"""$term.buildAwsValue()""")
      case ModelType.Exception =>
        ZIO.succeed(term)
      case ModelType.BigDecimal =>
        ZIO.succeed(q"""$term.bigDecimal""")
      case _ =>
        TypeMapping.toJavaType(model).map { typ =>
          q"""$term : $typ"""
        }
    }

  protected def wrapSdkValue(
      model: Model,
      term: Term,
      prefix: Term.Name => Term = identity
  ): ZIO[GeneratorContext, GeneratorFailure, Term] =
    model.typ match {
      case ModelType.Map =>
        for {
          keyModel <- get(model.shape.getMapKeyType.getShape)
          valueModel <- get(model.shape.getMapValueType.getShape)
          key = Term.Name("key")
          value = Term.Name("value")
          wrapKey <- wrapSdkValue(keyModel, key)
          wrapValue <- wrapSdkValue(valueModel, value)
        } yield
          if (wrapKey == key && wrapValue == value) {
            q"""$term.asScala.toMap"""
          } else {
            q"""$term.asScala.map { case (key, value) => $wrapKey -> $wrapValue }.toMap"""
          }
      case ModelType.List =>
        for {
          valueModel <- get(model.shape.getListMember.getShape)
          item = Term.Name("item")
          wrapItem <- wrapSdkValue(valueModel, item)
        } yield
          if (wrapItem == item) {
            q"""$term.asScala.toList"""
          } else {
            q"""$term.asScala.map { item => $wrapItem }.toList"""
          }
      case ModelType.Enum =>
        val nameTerm = prefix(Term.Name(model.name))
        ZIO.succeed(q"""$nameTerm.wrap($term)""")
      case ModelType.Blob =>
        ZIO.succeed(q"""Chunk.fromArray($term.asByteArrayUnsafe())""")
      case ModelType.Structure =>
        val nameTerm = prefix(Term.Name(model.name))
        ZIO.succeed(q"""$nameTerm.wrap($term)""")
      case ModelType.Exception =>
        ZIO.succeed(term)
      case _
          if TypeMapping.isPrimitiveType(model.shape) && !TypeMapping.isBuiltIn(
            model.shapeName
          ) =>
        ZIO.succeed(q"""$term : primitives.${Type.Name(model.name)}""")
      case _ =>
        ZIO.succeed(q"""$term : ${Type.Name(model.name)}""")
    }

  protected def propertyName(
      model: Model,
      fieldModel: Model,
      name: String
  ): ZIO[GeneratorContext, Nothing, PropertyNames] = {
    getNamingStrategy.flatMap { namingStrategy =>
      getModels.map { models =>
        val shapeModifiers = Option(
          models.customizationConfig().getShapeModifiers
        ).map(_.asScala).getOrElse(Map.empty[String, ShapeModifier])
        shapeModifiers
          .get(model.shapeName)
          .flatMap { shapeModifier =>
            val modifies = Option(shapeModifier.getModify)
              .map(_.asScala)
              .getOrElse(List.empty)
            val matchingModifiers = modifies.flatMap { modifiesMap =>
              modifiesMap.asScala
                .map { case (key, value) => (key.toLowerCase, value) }
                .get(name.toLowerCase)
            }.toList

            matchingModifiers
              .map(modifier => Option(modifier.getEmitPropertyName))
              .find(_.isDefined)
              .flatten
              .map(_.uncapitalize)
              .map(name => PropertyNames(name, name))
          }
          .getOrElse {
            val getterMethod = namingStrategy.getFluentGetterMethodName(
              name,
              model.shape,
              fieldModel.shape
            )

            val stripped = getterMethod
              .stripSuffix("AsString")
              .stripSuffix("AsStrings")
            if (fieldModel.typ == ModelType.String) {
              PropertyNames(getterMethod, stripped)
            } else {
              PropertyNames(stripped, stripped)
            }
          }
      }
    }
  }

  protected def writeIfDifferent(
      path: Path,
      contents: String
  ): ZIO[blocking.Blocking, GeneratorFailure, Unit] =
    Files.exists(path).flatMap { exists =>
      for {
        existingBytes <-
          if (exists) {
            Files.readAllBytes(path).mapError(FailedToReadFile)
          } else {
            ZIO.succeed(Chunk.empty[Byte])
          }
        contentsBytes =
          Chunk.fromArray(contents.getBytes(StandardCharsets.UTF_8))
        _ <-
          if (existingBytes == contentsBytes) {
            ZIO.unit
          } else {
            Files.writeBytes(path, contentsBytes).mapError(FailedToWriteFile)
          }
      } yield ()
    }

  protected def scalaVersion: String

  protected def prettyPrint(tree: Tree): String = {
    val dialect = 
      if (scalaVersion.startsWith("3.")) scala.meta.dialects.Scala3
      else if (scalaVersion.startsWith("2.13.")) scala.meta.dialects.Scala213
      else scala.meta.dialects.Scala212
    val prettyprinter = TreeSyntax[Tree](dialect)
    prettyprinter(tree).toString
  }
}
