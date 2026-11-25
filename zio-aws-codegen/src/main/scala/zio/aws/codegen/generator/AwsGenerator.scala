package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.GeneratorFailure
import software.amazon.awssdk.codegen.C2jModels
import zio._
import zio.aws.codegen.Parameters
import zio.aws.codegen.loader.ModuleId

import java.io.File

/** Generates the zio-aws client sources of a service and the list of published artifacts. */
trait AwsGenerator {
  /** Generates the client and model sources of the module `id` from `model` and returns the files that were written. */
  def generateServiceCode(
      id: ModuleId,
      model: C2jModels,
      sbtLogger: _root_.sbt.Logger
  ): ZIO[Any, GeneratorFailure[AwsGeneratorFailure], Set[
    File
  ]]

  /** Writes the artifact list file for the modules `ids`. */
  def generateArtifactList(
      ids: Set[ModuleId]
  ): ZIO[Any, GeneratorFailure[AwsGeneratorFailure], Unit]
}

/** Accessors and the live implementation of [[AwsGenerator]]. */
object AwsGenerator {

  /** A layer that builds an [[AwsGeneratorImpl]] from the `Parameters` in the environment. */
  val live: ZLayer[Parameters, Nothing, AwsGenerator] = ZLayer(
    ZIO.service[Parameters].map(AwsGeneratorImpl.apply)
  )

  /** Calls `generateServiceCode` on the [[AwsGenerator]] in the environment. */
  def generateServiceCode(
      id: ModuleId,
      model: C2jModels,
      sbtLogger: _root_.sbt.Logger
  ): ZIO[AwsGenerator, GeneratorFailure[
    AwsGeneratorFailure
  ], Set[
    File
  ]] =
    ZIO.serviceWithZIO[AwsGenerator](
      _.generateServiceCode(id, model, sbtLogger)
    )

  /** Calls `generateArtifactList` on the [[AwsGenerator]] in the environment. */
  def generateArtifactList(
      ids: Set[ModuleId]
  ): ZIO[AwsGenerator, GeneratorFailure[
    AwsGeneratorFailure
  ], Unit] =
    ZIO.serviceWithZIO[AwsGenerator](_.generateArtifactList(ids))
}
