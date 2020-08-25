package io.github.vigoo.zioaws.codegen

import sbt.Keys._
import sbt._
import _root_.io.github.vigoo.zioaws.codegen._
import _root_.io.github.vigoo.zioaws.codegen.loader._
import _root_.io.github.vigoo.zioaws.codegen.generator._
import _root_.io.github.vigoo.clipp.zioapi.config._
import zio._

object ZioAwsCodegenPlugin extends AutoPlugin {

  object autoImport {
    val getAllModelIds = taskKey[Set[ModelId]]("Gets all the AWS models on the classpath")
    val generateSubprojectsSbt = taskKey[Unit]("Generates the subprojects.sbt file")

    val awsLibraryId = settingKey[String]("Selects the AWS library to generate sources for")

    lazy val generateSources =
      Def.task {
        val idStr = awsLibraryId.value
        val id = ModelId.parse(idStr) match {
          case Left(failure) => sys.error(failure)
          case Right(value) => value
        }

        println(s"Generating sources for $id ($idStr)")
        Seq.empty[File]
      }
  }

  import autoImport._

  sealed trait Error

  case class ReflectionError(reason: Throwable) extends Error

  case class GeneratorError(error: GeneratorFailure) extends Error

  override lazy val buildSettings = Seq(
    generateSubprojectsSbt := generateSubprojectsSbtTask.value,
    getAllModelIds := getAllModelIdsTask.value,
  )

  lazy val getAllModelIdsTask =
    Def.task {
      zio.Runtime.default.unsafeRun {
        val env = loader.live
        val task = loader.findModels().mapError(ReflectionError)
        task.provideCustomLayer(env).catchAll {
          case ReflectionError(exception) =>
            zio.console.putStrErr(exception.toString).as(Set.empty[ModelId])
        }
      }
    }

  lazy val generateSubprojectsSbtTask =
    Def.task {
      val targetRoot = baseDirectory.value

      val ids = getAllModelIdsTask.value
      val params = Parameters(
        targetRoot = targetRoot.toPath,
      )

      zio.Runtime.default.unsafeRun {
        val cfg = ZLayer.succeed(new ClippConfig.Service[Parameters] {
          override val parameters: Parameters = params
        })
        val env = loader.live ++ (cfg >+> generator.live)
        val task = generator.generateSubprojectsSbt(ids)
        task.provideCustomLayer(env).catchAll { generatorError =>
          zio.console.putStrLnErr(s"Code generator failure: ${generatorError}")
        }
      }
    }
}
