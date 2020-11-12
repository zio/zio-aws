package io.github.vigoo.zioaws.codegen

import io.github.vigoo.zioaws.codegen.loader._
import io.github.vigoo.zioaws.codegen.generator._
import sbt.Keys._
import sbt.Project.projectToRef
import sbt.{Compile, Def, _}
import zio._
import zio.nio.core.file.Path

object ZioAwsCodegenPlugin extends AutoPlugin {

  object autoImport {
    val awsLibraryId =
      settingKey[String]("Selects the AWS library to generate sources for")
    val awsLibraryVersion = settingKey[String](
      "Specifies the version of the  AWS Java SDK to depend on"
    )
    val circleCiParallelJobs =
      settingKey[Int]("Number of parallel jobs in the generated circleCi file")
    val circleCiSource = settingKey[File]("circleCi source file")
    val circleCiTarget = settingKey[File]("circleCi target file")

    val generateCircleCiYaml =
      taskKey[Unit]("Regenerates the .circleCi.yml file")

    lazy val generateSources =
      Def.task {
        val log = streams.value.log

        val idStr = awsLibraryId.value
        val id = ModelId.parse(idStr) match {
          case Left(failure) => sys.error(failure)
          case Right(value)  => value
        }

        val targetRoot = (sourceManaged in Compile).value
        val circleCiSrc = circleCiSource.value
        val circleCiDst = circleCiTarget.value
        val parallelJobs = circleCiParallelJobs.value

        val params = Parameters(
          targetRoot = Path.fromJava(targetRoot.toPath),
          circleCiSource = Path.fromJava(circleCiSrc.toPath),
          circleCiTarget = Path.fromJava(circleCiDst.toPath),
          parallelCircleCiJobs = parallelJobs
        )

        zio.Runtime.default.unsafeRun {
          val cfg = ZLayer.succeed(params)
          val env = loader.live ++ (cfg >+> generator.live)
          val task =
            for {
              _ <- ZIO.effect(log.info(s"Generating sources for $id"))
              model <- loader.loadCodegenModel(id)
              files <- generator.generateServiceCode(id, model)
            } yield files.toSeq
          task.provideCustomLayer(env).catchAll { generatorError =>
            ZIO
              .effect(log.error(s"Code generator failure: ${generatorError}"))
              .as(Seq.empty)
          }
        }
      }
  }

  import autoImport._

  sealed trait Error

  case class ReflectionError(reason: Throwable) extends Error

  case class GeneratorError(error: GeneratorFailure) extends Error

  override lazy val projectSettings = {
    generateCircleCiYaml := generateCircleCiYamlTask.value
  }

  override lazy val extraProjects: Seq[Project] = {
    zio.Runtime.default.unsafeRun {
      val env = loader.live
      val task = for {
        ids <- loader.findModels()
      } yield generateSbtSubprojects(ids)

      task.provideCustomLayer(env).catchAll { generatorError =>
        zio.console
          .putStrLnErr(s"Code generator failure: ${generatorError}")
          .as(Seq.empty)
      }
    }
  }

  private lazy val generateCircleCiYamlTask = Def.task {
    val log = streams.value.log

    val targetRoot = (sourceManaged in Compile).value
    val circleCiSrc = circleCiSource.value
    val circleCiDst = circleCiTarget.value
    val parallelJobs = circleCiParallelJobs.value

    val params = Parameters(
      targetRoot = Path.fromJava(targetRoot.toPath),
      circleCiSource = Path.fromJava(circleCiSrc.toPath),
      circleCiTarget = Path.fromJava(circleCiDst.toPath),
      parallelCircleCiJobs = parallelJobs
    )

    zio.Runtime.default.unsafeRun {
      val cfg = ZLayer.succeed(params)
      val env = loader.live ++ (cfg >+> generator.live)
      val task =
        for {
          _ <- ZIO.effect(log.info(s"Regenerating $circleCiDst"))
          ids <- loader.findModels()
          _ <- generator.generateCircleCiYaml(ids)
        } yield ()
      task.provideCustomLayer(env).catchAll { generatorError =>
        ZIO
          .effect(log.error(s"Code generator failure: ${generatorError}"))
          .as(Seq.empty)
      }
    }
  }

  protected def generateSbtSubprojects(ids: Set[ModelId]): Seq[Project] = {
    val map = ids.toSeq
      .sortWith { case (a, b) =>
        val aIsDependent = a.subModuleName match {
          case Some(value) if value != a.name => true
          case _                              => false
        }
        val bIsDependent = b.subModuleName match {
          case Some(value) if value != b.name => true
          case _                              => false
        }

        bIsDependent || (!aIsDependent && a.toString < b.toString)
      }
      .foldLeft(Map.empty[ModelId, Project]) { (mapping, id) =>
        val name = id.moduleName
        val fullName = s"zio-aws-$name"
        val deps: Seq[ClasspathDep[ProjectReference]] = id.subModule match {
          case Some(value) if value != id.name =>
            Seq(
              ClasspathDependency(LocalProject("zio-aws-core"), None),
              ClasspathDependency(
                mapping(ModelId(id.name, Some(id.name))),
                None
              )
            )
          case _ =>
            Seq(ClasspathDependency(LocalProject("zio-aws-core"), None))
        }

        val project = Project(fullName, file("generated") / name)
          .settings(
            libraryDependencies += "software.amazon.awssdk" % id.name % awsLibraryVersion.value,
            awsLibraryId := id.toString,
            Compile / sourceGenerators += generateSources.taskValue,
            mappings in (Compile, packageSrc) ++= {
              val base = (sourceManaged in Compile).value
              val files = (managedSources in Compile).value
              files.map { f => (f, f.relativeTo(base).get.getPath) }
            }
          )
          .dependsOn(deps: _*)

        mapping.updated(id, project)
      }

    val projects = map.values.toSeq
    val aggregated = Project("all", file("generated") / "all")
      .settings(
        publishArtifact := false
      )
      .aggregate(projects.map(projectToRef): _*)

    projects :+ aggregated
  }
}
