package io.github.vigoo.zioaws.codegen.generator

import java.nio.charset.StandardCharsets

import io.github.vigoo.zioaws.codegen.generator.context._
import io.github.vigoo.zioaws.codegen.generator.syntax._
import software.amazon.awssdk.codegen.internal.Utils
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
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Term] =
    model.typ match {
      case ModelType.Map =>
        for {
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
        if (forceToString) {
          ZIO.succeed(q"""$term.unwrap.toString""")
        } else {
          ZIO.succeed(q"""$term.unwrap""")
        }
      case ModelType.Blob =>
        ZIO.succeed(q"""${Types.sdkBytes.term}.fromByteArrayUnsafe($term.toArray[Byte])""")
      case ModelType.Structure =>
        ZIO.succeed(q"""$term.buildAwsValue()""")
      case ModelType.Exception =>
        ZIO.succeed(term)
      case ModelType.BigDecimal =>
        ZIO.succeed(q"""$term.bigDecimal""")
      case _ =>
        TypeMapping.toJavaType(model).map { javaType =>
          q"""$term : ${javaType.typ}"""
        }
    }

  protected def wrapSdkValue(
      model: Model,
      term: Term
  ): ZIO[AwsGeneratorContext, AwsGeneratorFailure, Term] =
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
        ZIO.succeed(
          q"""${model.generatedType.term}.wrap($term)"""
        )
      case ModelType.Blob =>
        ZIO.succeed(q"""${Types.chunk_.term}.fromArray($term.asByteArrayUnsafe())""")
      case ModelType.Structure =>
        ZIO.succeed(
          q"""${model.generatedType.term}.wrap($term)"""
        )
      case ModelType.Exception =>
        ZIO.succeed(term)
      case _ =>
        ZIO.succeed(q"""$term : ${model.generatedType.typ}""")
    }

  protected def propertyName(
      model: Model,
      fieldModel: Model,
      name: String
  ): ZIO[AwsGeneratorContext, Nothing, PropertyNames] = {
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

            val stripped =
              if (
                Utils.isOrContainsEnumShape(
                  fieldModel.shape,
                  models.serviceModel().getShapes()
                )
              ) {
                getterMethod
                  .stripSuffix("AsString")
                  .stripSuffix("AsStrings")
              } else {
                getterMethod
              }
            if (fieldModel.typ == ModelType.String) {
              PropertyNames(getterMethod, stripped)
            } else {
              PropertyNames(stripped, stripped)
            }
          }
      }
    }
  }

  protected def scalaVersion: String
}
