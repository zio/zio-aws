package io.github.vigoo.zioaws.codegen

import java.io.File
import java.nio.file.Path

import cats.Traverse
import cats.instances.list._
import cats.instances.either._
import io.github.vigoo.clipp.ParameterParser
import io.github.vigoo.clipp.parsers._
import io.github.vigoo.clipp.syntax._
import io.github.vigoo.zioaws.codegen.loader.ModelId

case class Parameters(targetRoot: Path)

//object Parameters {
//  private implicit val modelIdParser: ParameterParser[ModelId] = new ParameterParser[ModelId] {
//    override def parse(value: String): Either[String, ModelId] =
//      value.split(':').toList match {
//        case List(single) => Right(ModelId(single, None))
//        case List(main, sub) => Right(ModelId(main, Some(sub)))
//        case _ => Left(s"Failed to parse $value as ModelId, use 'svc' or 'svc:subsvc' syntax.")
//      }
//
//    override def default: ModelId = ModelId("service", None)
//  }
//
//  private implicit def listParser[T : ParameterParser]: ParameterParser[List[T]] = new ParameterParser[List[T]] {
//    override def parse(value: String): Either[String, List[T]] =
//      Traverse[List].sequence(value.split(',').toList.map(_.trim).map(implicitly[ParameterParser[T]].parse))
//
//    override def default: List[T] = List(implicitly[ParameterParser[T]].default)
//  }
//
//  val spec = for {
//    _ <- metadata("zio-aws-codegen", "Code generator of the zio-aws modules")
//    targetRoot <- namedParameter[File]("Target root directory", "DIR", "target-root")
//    sourceRoot <- namedParameter[File]("Source root directory", "DIR", "source-root")
//    version <- namedParameter[String]("Version", "VER", "version")
//    scalaVersion <- namedParameter[String]("Scala version", "VER", "scala-version")
//    zioVersion <- namedParameter[String]("Version of ZIO", "VER", "zio-version")
//    zioInteropReactiveStreamsVersion <- namedParameter[String]("Version of zio-interop-reactivestreams", "VER", "zio-rs-version")
//    serviceList <- optional {
//      namedParameter[List[ModelId]]("Explicit list of models to be generated", "SVCS", "services")
//    }
//  } yield Parameters(targetRoot.toPath, sourceRoot.toPath, version, scalaVersion, zioVersion, zioInteropReactiveStreamsVersion, serviceList)
//
//}
