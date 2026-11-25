package zio.aws.codegen

import zio.aws.codegen.loader._
import zio.aws.codegen.generator._
import sbt.Keys._
import sbt.Project.projectToRef
import sbt.{Compile, Def, _}
import zio._
import zio.Unsafe.unsafe
import zio.nio.file.Path

/** The sbt plugin that generates the zio-aws service clients. It adds one sbt project per AWS SDK service module found by the loader (see `extraProjects`), each with a source generator that writes the client sources into `sourceManaged`, and the `generateArtifactList` task. A build enables it with `enablePlugins(ZioAwsCodegenPlugin)`.
  */
object ZioAwsCodegenPlugin extends AutoPlugin {

  /** The keys the plugin exposes to build definitions. */
  object autoImport {
    /** Identifies the AWS service module a project generates sources for, written as `svc` or `svc:subsvc`. The plugin sets it on each project it creates, and `generateSources` parses it with `ModuleId.parse`. */
    val awsLibraryId =
      settingKey[String]("Selects the AWS library to generate sources for")
    /** The version of the `software.amazon.awssdk` service artifact that each generated project depends on. */
    val awsLibraryVersion = settingKey[String](
      "Specifies the version of the  AWS Java SDK to depend on"
    )
    /** The version of `dev.zio` `zio` and `zio-streams` that each generated project depends on. */
    val zioLibraryVersion = settingKey[String](
      "Specifies the version of the ZIO library to depend on"
    )
    /** The value that `ZioAwsCi.buildJobs` and `ZioAwsCi.releaseJobs` divide the module count by to size each group of client modules built in one CI job. */
    val ciParallelJobs =
      settingKey[Int]("Number of parallel jobs in the generated circleCi file")
    /** The names of the generated projects (such as `zio-aws-ec2`) that get a CI job of their own instead of being grouped with the others. */
    val ciSeparateJobs = settingKey[Seq[String]](
      "List of subprojects to have their individual circleCi jobs"
    )
    /** The file that `generateArtifactList` writes the artifact list to. */
    val artifactListTarget =
      settingKey[File]("artifact list markdown target file")

    /** Finds the AWS service modules and writes the artifact list to `artifactListTarget`. A generator failure is logged and does not fail the task. */
    val generateArtifactList =
      taskKey[Unit]("Regenerates the artifact list markdown file")

    /** The source generator of each generated project. It parses `awsLibraryId`, loads that module's models with the git-based loader, and returns the files it writes under `Compile / sourceManaged`. A generator failure is logged and then thrown. */
    lazy val generateSources =
      Def.task {
        val log = streams.value.log

        val idStr = awsLibraryId.value
        val id = ModuleId.parse(idStr) match {
          case Left(failure) => sys.error(failure)
          case Right(value)  => value
        }

        val targetRoot = (Compile / sourceManaged).value
        val parallelJobs = ciParallelJobs.value
        val separateJobs = ciSeparateJobs.value
        val artifactLstTarget = artifactListTarget.value
        val ver = version.value
        val scalaVer = scalaVersion.value

        val params = Parameters(
          targetRoot = Path.fromJava(targetRoot.toPath),
          parallelCiJobs = parallelJobs,
          separateCiJobs = separateJobs.toSet,
          artifactListTarget = Path.fromJava(artifactLstTarget.toPath),
          version = ver,
          scalaVersion = scalaVer
        )

        unsafe { implicit u =>
          zio.Runtime.default.unsafe
            .run {
              val cfg = ZLayer.succeed(params)
              val env =
                Loader.cached(Loader.fromGit) ++ (cfg >+> AwsGenerator.live)
              val task =
                for {
                  _ <- ZIO.attempt(log.info(s"Generating sources for $id"))
                  model <- Loader.loadCodegenModel(id)
                  files <- AwsGenerator.generateServiceCode(id, model, log)
                } yield files.toSeq
              task.provideLayer(env).tapError { generatorError =>
                ZIO
                  .attempt(
                    log.error(s"Code generator failure: ${generatorError}")
                  )
              }
            }
            .getOrThrowFiberFailure()
        }
      }
  }

  import autoImport._

  /** The base type of the errors this plugin defines. */
  sealed trait Error

  /** An error that wraps the `Throwable` in `reason`. */
  case class ReflectionError(reason: Throwable) extends Error

  /** An error that wraps the generator failure in `error`. */
  case class GeneratorError(error: AwsGeneratorFailure) extends Error

  override lazy val projectSettings = Seq(
    generateArtifactList := generateArtifactListTask.value
  )

  /** The `ModuleId.moduleName` of each module the loader found (the sub-module name when there is one, otherwise the service name). It is empty until `extraProjects` is evaluated while the build loads. */
  @volatile var moduleNames: Set[String] = Set.empty

  override lazy val extraProjects: Seq[Project] = {
    unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run {
          val env = Loader.cached(Loader.fromGit)
          val task = for {
            ids <- Loader.findModels()
          } yield {
            moduleNames = ids.map(_.moduleName)
            generateSbtSubprojects(ids)
          }

          task.provideLayer(env).tapError { generatorError =>
            zio.Console
              .printLineError(s"Code generator failure: ${generatorError}")
          }
        }
        .getOrThrowFiberFailure()
    }
  }

  private lazy val generateArtifactListTask = Def.task {
    val log = streams.value.log
    val targetRoot = (Compile / sourceManaged).value
    val parallelJobs = ciParallelJobs.value
    val separateJobs = ciSeparateJobs.value
    val artifactLstTarget = artifactListTarget.value
    val ver = version.value
    val scalaVer = scalaVersion.value

    val params = Parameters(
      targetRoot = Path.fromJava(targetRoot.toPath),
      parallelCiJobs = parallelJobs,
      separateCiJobs = separateJobs.toSet,
      artifactListTarget = Path.fromJava(artifactLstTarget.toPath),
      version = ver,
      scalaVersion = scalaVer
    )

    unsafe { implicit u =>
      zio.Runtime.default.unsafe
        .run {
          val cfg = ZLayer.succeed(params)
          val env = Loader.cached(Loader.fromGit) ++ (cfg >+> AwsGenerator.live)
          val task =
            for {
              _ <- ZIO.attempt(
                log.info(s"Regenerating ${params.artifactListTarget}")
              )
              ids <- Loader.findModels()
              _ <- AwsGenerator.generateArtifactList(ids)
            } yield ()
          task.provideLayer(env).catchAll { generatorError =>
            ZIO
              .attempt(log.error(s"Code generator failure: ${generatorError}"))
              .as(Seq.empty)
          }
        }
        .getOrThrowFiberFailure()
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
}
