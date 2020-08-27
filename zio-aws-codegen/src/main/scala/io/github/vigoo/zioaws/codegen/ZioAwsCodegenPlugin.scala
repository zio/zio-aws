package io.github.vigoo.zioaws.codegen

import sbt.Keys._
import sbt.{Compile, Def, _}
import _root_.io.github.vigoo.zioaws.codegen._
import _root_.io.github.vigoo.zioaws.codegen.loader._
import _root_.io.github.vigoo.zioaws.codegen.generator._
import _root_.io.github.vigoo.clipp.zioapi.config._
import zio._

object ZioAwsCodegenPlugin extends AutoPlugin {

  object autoImport {
    val awsLibraryId = settingKey[String]("Selects the AWS library to generate sources for")
    val awsLibraryVersion = settingKey[String]("Specifies the version of the  AWS Java SDK to depend on")

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

  override lazy val extraProjects: Seq[Project] = {
    zio.Runtime.default.unsafeRun {
      val env = loader.live
      val task = for {
        ids <- loader.findModels()
      } yield generateSbtSubprojects(ids)

      task.provideCustomLayer(env).catchAll { generatorError =>
        zio.console.putStrLnErr(s"Code generator failure: ${generatorError}").as(Seq.empty)
      }
    }
  }

  protected def generateSbtSubprojects(ids: Set[ModelId]): Seq[Project] = {
    val map = ids
      .toSeq
      .sortWith { case (a, b) =>
        val aIsDependent = a.subModuleName match {
          case Some(value) if value != a.name => true
          case _ => false
        }
        val bIsDependent = b.subModuleName match {
          case Some(value) if value != b.name => true
          case _ => false
        }

        bIsDependent || (!aIsDependent && a.toString < b.toString)
      }
      .foldLeft(Map.empty[ModelId, Project]) { (mapping, id) =>
        val name = id.moduleName
        val fullName = s"zio-aws-$name"
        val deps: Seq[ClasspathDep[ProjectReference]] = id.subModule match {
          case Some(value) if value != id.name =>
            Seq(ClasspathDependency(LocalProject("zio-aws-core"), None),
              ClasspathDependency(mapping(ModelId(id.name, Some(id.name))), None))
          case _ =>
            Seq(ClasspathDependency(LocalProject("zio-aws-core"), None))
        }

        val project = Project(fullName, file("generated") / name)
          .settings(
            libraryDependencies += "software.amazon.awssdk" % id.name % awsLibraryVersion.value,
            awsLibraryId := id.toString,
            Compile / sourceGenerators += generateSources.taskValue)
          .dependsOn(deps: _*)

        mapping.updated(id, project)
      }

    map.values.toSeq
  }
}
