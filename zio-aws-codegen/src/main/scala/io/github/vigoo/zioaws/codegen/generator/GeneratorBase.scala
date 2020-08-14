package io.github.vigoo.zioaws.codegen.generator

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import io.github.vigoo.zioaws.codegen.generator.context._
import zio.{Chunk, ZIO}

import scala.meta._

trait GeneratorBase {

  protected def unwrapSdkValue(model: Model, term: Term, forceToString: Boolean = false): ZIO[GeneratorContext, GeneratorFailure, Term] =
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
        } yield if (unwrapKey == key && unwrapValue == value) {
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
        } yield if (unwrapItem == item) {
          q"""$term.asJava"""
        } else {
          q"""$term.map { item => $unwrapItem }.asJava"""
        }
      case ModelType.Enum =>
        val nameTerm = Term.Name(model.name)
        if (forceToString) {
          ZIO.succeed(q"""$term.unwrap.toString""")
        } else {
          ZIO.succeed(q"""$term.unwrap""")
        }
      case ModelType.Blob =>
        ZIO.succeed(q"""SdkBytes.fromByteArray($term.toArray[Byte])""")
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

  protected def wrapSdkValue(model: Model, term: Term): ZIO[GeneratorContext, GeneratorFailure, Term] =
    model.typ match {
      case ModelType.Map =>
        for {
          keyModel <- get(model.shape.getMapKeyType.getShape)
          valueModel <- get(model.shape.getMapValueType.getShape)
          key = Term.Name("key")
          value = Term.Name("value")
          wrapKey <- wrapSdkValue(keyModel, key)
          wrapValue <- wrapSdkValue(valueModel, value)
        } yield if (wrapKey == key && wrapValue == value) {
          q"""$term.asScala.toMap"""
        } else {
          q"""$term.asScala.map { case (key, value) => $wrapKey -> $wrapValue }.toMap"""
        }
      case ModelType.List =>
        for {
          valueModel <- get(model.shape.getListMember.getShape)
          item = Term.Name("item")
          wrapItem <- wrapSdkValue(valueModel, item)
        } yield if (wrapItem == item) {
          q"""$term.asScala.toList"""
        } else {
          q"""$term.asScala.map { item => $wrapItem }.toList"""
        }
      case ModelType.Enum =>
        val nameTerm = Term.Name(model.name)
        ZIO.succeed(q"""$nameTerm.wrap($term)""")
      case ModelType.Blob =>
        ZIO.succeed(q"""Chunk.fromByteBuffer($term.asByteBuffer())""")
      case ModelType.Structure =>
        val nameTerm = Term.Name(model.name)
        ZIO.succeed(q"""$nameTerm.wrap($term)""")
      case ModelType.Exception =>
        ZIO.succeed(term)
      case _ if TypeMapping.isPrimitiveType(model.shape) && !TypeMapping.isBuiltIn(model.shapeName) =>
        ZIO.succeed(q"""$term : primitives.${Type.Name(model.name)}""")
      case _ =>
        ZIO.succeed(q"""$term : ${Type.Name(model.name)}""")
    }

  def writeIfDifferent(path: Path, contents: String): ZIO[Any, GeneratorFailure, Unit] =
    ZIO(Files.exists(path)).mapError(FailedToReadFile).flatMap { exists =>
      ZIO {
        if (exists) {
          Files.readAllBytes(path)
        } else {
          Array.empty[Byte]
        }
      }.map(Chunk.fromArray[Byte]).mapError(FailedToReadFile).flatMap { existingBytes =>
        val contentsBytes = Chunk.fromArray(contents.getBytes(StandardCharsets.UTF_8))
        if (existingBytes == contentsBytes) {
          ZIO.unit
        } else {
          ZIO(Files.write(path, contentsBytes.toArray)).mapError(FailedToWriteFile).unit
        }
      }
    }

}
