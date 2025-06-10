package zio.aws.codegen

import sbt.Keys.*
import sbt.Project.projectToRef
import sbt.{Compile, Def, *}
import zio.*
import zio.Unsafe.unsafe
import zio.aws.codegen.generator.*
import zio.aws.codegen.loader.*
import zio.nio.file.Path

object ZioAwsCodegenPlugin extends AutoPlugin {

  object autoImport {
    val awsLibraryId =
      settingKey[String]("Selects the AWS library to generate sources for")
    val awsLibraryVersion = settingKey[String](
      "Specifies the version of the  AWS Java SDK to depend on"
    )
    val zioLibraryVersion = settingKey[String](
      "Specifies the version of the ZIO library to depend on"
    )
    val zioMockLibraryVersion = settingKey[String](
      "Specifies the version of the ZIO Mock library to depend on"
    )
    val ciParallelJobs =
      settingKey[Int]("Number of parallel jobs in the generated circleCi file")
    val ciSeparateJobs = settingKey[Seq[String]](
      "List of subprojects to have their individual circleCi jobs"
    )
    val ciTarget = settingKey[File]("circleCi target file")
    val artifactListTarget =
      settingKey[File]("artifact list markdown target file")

    val generateCiYaml = taskKey[Unit]("Regenerates the CI workflow file")
    val generateArtifactList =
      taskKey[Unit]("Regenerates the artifact list markdown file")

    lazy val generateSources =
      Def.task {
        val log = streams.value.log

        val idStr = awsLibraryId.value
        val id = ModuleId.parse(idStr) match {
          case Left(failure) => sys.error(failure)
          case Right(value)  => value
        }

        val targetRoot = (Compile / sourceManaged).value
        val githubCiDst = ciTarget.value
        val parallelJobs = ciParallelJobs.value
        val separateJobs = ciSeparateJobs.value
        val artifactLstTarget = artifactListTarget.value
        val ver = version.value
        val scalaVer = scalaVersion.value

        val params = Parameters(
          targetRoot = Path.fromJava(targetRoot.toPath),
          ciTarget = Path.fromJava(githubCiDst.toPath),
          parallelCiJobs = parallelJobs,
          separateCiJobs = separateJobs.toSet,
          artifactListTarget = Path.fromJava(artifactLstTarget.toPath),
          version = ver,
          scalaVersion = scalaVer
        )

        unsafeRun {
          val cfg = ZLayer.succeed(params)
          val env =
            Loader.cached(Loader.fromGit) ++ (cfg >+> AwsGenerator.live)
          val task =
            for {
              _ <- ZIO.attempt(log.info(s"Generating sources for $id"))
              model <- ZIO.serviceWithZIO[Loader](_.loadCodegenModel(id))
              files <- ZIO.serviceWithZIO[AwsGenerator](
                _.generateServiceCode(id, model, log)
              )
            } yield files.toSeq
          task.provide(env).tapError { generatorError =>
            ZIO
              .attempt(
                log.error(s"Code generator failure: ${generatorError}")
              )
          }
        }
      }
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    generateCiYaml := generateCiYamlTask.value,
    generateArtifactList := generateArtifactListTask.value
  )

  override lazy val extraProjects: Seq[Project] = {
    unsafeRun {
      val env = Loader.cached(Loader.fromGit)
      val task =
        ZIO.serviceWithZIO[Loader](_.findModels).map(generateSbtSubprojects)

      task.provide(env).tapError { generatorError =>
        zio.Console
          .printLineError(s"Code generator failure: ${generatorError}")
      }
    }
  }

  private lazy val generateCiYamlTask = Def.task {
    val log = streams.value.log
    val targetRoot = (Compile / sourceManaged).value
    val githubCiDst = ciTarget.value
    val parallelJobs = ciParallelJobs.value
    val separateJobs = ciSeparateJobs.value
    val artifactLstTarget = artifactListTarget.value
    val ver = version.value
    val scalaVer = scalaVersion.value

    val params = Parameters(
      targetRoot = Path.fromJava(targetRoot.toPath),
      ciTarget = Path.fromJava(githubCiDst.toPath),
      parallelCiJobs = parallelJobs,
      separateCiJobs = separateJobs.toSet,
      artifactListTarget = Path.fromJava(artifactLstTarget.toPath),
      version = ver,
      scalaVersion = scalaVer
    )

    unsafeRun {
      val cfg = ZLayer.succeed(params)
      val env = Loader.cached(Loader.fromGit) ++ (cfg >+> AwsGenerator.live)
      val task =
        for {
          _ <- ZIO.attempt(log.info(s"Regenerating ${params.ciTarget}"))
          ids <- ZIO.serviceWithZIO[Loader](_.findModels)
          _ <- ZIO.serviceWithZIO[AwsGenerator](_.generateCiYaml(ids))
        } yield ()
      task.provide(env).catchAll { generatorError =>
        ZIO
          .attempt(log.error(s"Code generator failure: ${generatorError}"))
          .as(Seq.empty)
      }
    }
  }

  private lazy val generateArtifactListTask = Def.task {
    val log = streams.value.log
    val targetRoot = (Compile / sourceManaged).value
    val circleCiDst = ciTarget.value
    val parallelJobs = ciParallelJobs.value
    val separateJobs = ciSeparateJobs.value
    val artifactLstTarget = artifactListTarget.value
    val ver = version.value
    val scalaVer = scalaVersion.value

    val params = Parameters(
      targetRoot = Path.fromJava(targetRoot.toPath),
      ciTarget = Path.fromJava(circleCiDst.toPath),
      parallelCiJobs = parallelJobs,
      separateCiJobs = separateJobs.toSet,
      artifactListTarget = Path.fromJava(artifactLstTarget.toPath),
      version = ver,
      scalaVersion = scalaVer
    )

    unsafeRun {
      val cfg = ZLayer.succeed(params)
      val env = Loader.cached(Loader.fromGit) ++ (cfg >+> AwsGenerator.live)
      val task =
        for {
          _ <- ZIO.attempt(
            log.info(s"Regenerating ${params.artifactListTarget}")
          )
          ids <- ZIO.serviceWithZIO[Loader](_.findModels)
          _ <- ZIO.serviceWithZIO[AwsGenerator](_.generateArtifactList(ids))
        } yield ()
      task.provide(env).catchAll { generatorError =>
        ZIO
          .attempt(log.error(s"Code generator failure: ${generatorError}"))
          .as(Seq.empty)
      }
    }
  }

  protected def generateSbtSubprojects(ids: Set[ModuleId]): Seq[Project] = {
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
      // .filter(name => Set("elasticbeanstalk", "kinesis", "dynamodb", "s3", "ec2").contains(name.moduleName))
      .foldLeft(Map.empty[ModuleId, Project]) { (mapping, id) =>
        val name = id.moduleName
        val fullName = s"zio-aws-$name"
        val deps: Seq[ClasspathDep[ProjectReference]] = id.subModule match {
          case Some(value) if value != id.name =>
            Seq(
              ClasspathDependency(LocalProject("zio-aws-core"), None),
              ClasspathDependency(
                mapping(ModuleId(id.name, None)),
                None
              )
            )
          case _ =>
            Seq(ClasspathDependency(LocalProject("zio-aws-core"), None))
        }

        val project = Project(fullName, file("generated") / name)
          .settings(
            libraryDependencies += "software.amazon.awssdk" % id.name % awsLibraryVersion.value,
            libraryDependencies += "dev.zio" %% "zio" % zioLibraryVersion.value,
            libraryDependencies += "dev.zio" %% "zio-streams" % zioLibraryVersion.value,
            libraryDependencies += "dev.zio" %% "zio-mock" % zioMockLibraryVersion.value,
            awsLibraryId := id.toString,
            Compile / sourceGenerators += generateSources.taskValue,
            Compile / packageSrc / mappings ++= {
              val base = (Compile / sourceManaged).value
              val files = (Compile / managedSources).value
              files.map { f => (f, f.relativeTo(base).get.getPath) }
            },
            Compile / doc / sources := Seq.empty
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

  private def unsafeRun[R, E, A](code: => ZIO[Any, E, A]): A =
    unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run(code)
        .getOrThrowFiberFailure()
    }

}
