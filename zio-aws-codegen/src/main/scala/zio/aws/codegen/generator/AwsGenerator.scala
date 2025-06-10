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
  ): ZIO[Any, GeneratorFailure[AwsGeneratorFailure], Set[
    File
  ]]

  def generateCiYaml(
      ids: Set[ModuleId]
  ): ZIO[Any, GeneratorFailure[AwsGeneratorFailure], Unit]

  def generateArtifactList(
      ids: Set[ModuleId]
  ): ZIO[Any, GeneratorFailure[AwsGeneratorFailure], Unit]
}

object AwsGenerator {

  val live: ZLayer[Parameters, Nothing, AwsGenerator] = ZLayer(
    ZIO.serviceWith[Parameters](AwsGeneratorImpl.apply)
  )

}
