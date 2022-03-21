package zio.aws.codegen.generator

import io.github.vigoo.metagen.core.GeneratorFailure
import software.amazon.awssdk.codegen.C2jModels
import zio._
import zio.aws.codegen.Parameters
import zio.aws.codegen.loader.ModuleId

import java.io.File

trait AwsGenerator {
  def generateServiceCode(
      id: ModuleId,
      model: C2jModels,
      sbtLogger: sbt.Logger
  ): ZIO[Console, GeneratorFailure[AwsGeneratorFailure], Set[
    File
  ]]

  def generateCiYaml(
      ids: Set[ModuleId]
  ): ZIO[Console, GeneratorFailure[AwsGeneratorFailure], Unit]

  def generateArtifactList(
      ids: Set[ModuleId]
  ): ZIO[Console, GeneratorFailure[AwsGeneratorFailure], Unit]
}

object AwsGenerator {

  val live: ZLayer[Parameters, Nothing, AwsGenerator] = AwsGeneratorImpl.toLayer

  def generateServiceCode(
      id: ModuleId,
      model: C2jModels,
      sbtLogger: sbt.Logger
  ): ZIO[AwsGenerator with Console, GeneratorFailure[
    AwsGeneratorFailure
  ], Set[
    File
  ]] =
    ZIO.serviceWithZIO[AwsGenerator](
      _.generateServiceCode(id, model, sbtLogger)
    )

  def generateCiYaml(
      ids: Set[ModuleId]
  ): ZIO[AwsGenerator with Console, GeneratorFailure[
    AwsGeneratorFailure
  ], Unit] =
    ZIO.serviceWithZIO[AwsGenerator](_.generateCiYaml(ids))

  def generateArtifactList(
      ids: Set[ModuleId]
  ): ZIO[AwsGenerator with Console, GeneratorFailure[
    AwsGeneratorFailure
  ], Unit] =
    ZIO.serviceWithZIO[AwsGenerator](_.generateArtifactList(ids))
}
