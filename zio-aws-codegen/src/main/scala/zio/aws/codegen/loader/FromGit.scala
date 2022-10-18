package zio.aws.codegen.loader

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.TextProgressMonitor
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.internal.Jackson
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{
  Paginators,
  ServiceModel,
  Waiters
}
import zio.nio.file.{Files, Path}
import zio.{Ref, Semaphore, System, ZIO}

import java.io.{File, FileNotFoundException}
import java.util.Properties
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

case class FromGit(modules: Ref[Map[ModuleId, Path]], semaphore: Semaphore)
    extends Loader {

  override def findModels(): ZIO[Any, Throwable, Set[ModuleId]] =
    getOrCollectModules.map { moduleMap =>
      moduleMap.keySet
    }

  override def loadCodegenModel(id: ModuleId): ZIO[Any, Throwable, C2jModels] =
    getOrCollectModules.flatMap { moduleMap =>
      moduleMap.get(id) match {
        case Some(servicePath) =>
          val root = servicePath.parent.get
          for {
            serviceModel <- loadServiceModel(root)
            customizationConfig <- loadCustomizationModel(root)
            waiters <- loadWaiters(root)
            paginators <- loadPaginators(root)
            model <- ZIO.attempt {
              C2jModels
                .builder()
                .customizationConfig(customizationConfig)
                .serviceModel(serviceModel)
                .waitersModel(waiters)
                .paginatorsModel(paginators)
                .build()
            }
          } yield model
        case None => ZIO.fail(new RuntimeException(s"Could not find $id"))
      }
    }

  private def getOrCollectModules: ZIO[Any, Throwable, Map[ModuleId, Path]] =
    semaphore.withPermit {
      modules.get.flatMap { existing =>
        if (existing.isEmpty) {
          collectModules().tap(collected => modules.set(collected))
        } else {
          ZIO.succeed(existing)
        }
      }
    }

  private def collectModules(): ZIO[Any, Throwable, Map[ModuleId, Path]] =
    for {
      version <- ZIO.attempt {
        val props = new Properties()
        props.load(
          classOf[C2jModels].getResourceAsStream(
            "/META-INF/maven/software.amazon.awssdk/codegen/pom.properties"
          )
        )
        props.getProperty("version")
      }
      result <- ZIO.scoped {
        for {
          tempDir <- Files.createTempDirectoryScoped(None, Iterable.empty)
          _ <- ZIO.logInfo(s"AWS SDK version is $version")
          maybeRepo <- System.env("AWS_JAVA_SDK_REPOSITORY")
          repoDir <- maybeRepo match {
            case Some(repo) =>
              for {
                _ <- ZIO.logInfo(
                  s"Using configured AWS Java SDK repository: $repo"
                )
                _ <- ZIO.attempt {
                  Git
                    .open(new File(repo))
                    .checkout()
                    .setName("master")
                    .setProgressMonitor(new TextProgressMonitor())
                    .call()
                }
                _ <- ZIO.logInfo(s"Updating repository...")
                _ <- ZIO.attempt {
                  Git
                    .open(new File(repo))
                    .pull()
                    .setProgressMonitor(new TextProgressMonitor())
                    .call()
                }
                _ <- ZIO.logInfo(s"Checking out version by tag...")
                _ <- ZIO.attempt {
                  Git
                    .open(new File(repo))
                    .checkout()
                    .setCreateBranch(false)
                    .setName(version)
                    .setStartPoint(s"refs/tags/$version")
                    .setProgressMonitor(new TextProgressMonitor())
                    .call()
                }
              } yield Path(repo)
            case None =>
              for {
                _ <- ZIO.logInfo(s"AWS Java SDK clone path is $tempDir")
                _ <- ZIO.logInfo("Cloning AWS Java SDK repository...")
                _ <- ZIO.attempt {
                  Git
                    .cloneRepository()
                    .setURI("https://github.com/aws/aws-sdk-java-v2.git")
                    .setDirectory(tempDir.toFile)
                    .setBranchesToClone(
                      List(s"refs/tags/$version").asJavaCollection
                    )
                    .setBranch(s"refs/tags/$version")
                    .setProgressMonitor(new TextProgressMonitor())
                    .call()
                }
                _ <- ZIO.logInfo(
                  "Cloned AWS Java SDK repository, looking for service descriptors"
                )
              } yield tempDir
          }

          serviceJsons <- Files
            .find(repoDir) { case (path, attribs) =>
              path.endsWith(Path("service-2.json")) &&
              path.startsWith(repoDir / "services") &&
              attribs.isRegularFile
            }
            .runCollect
        } yield serviceJsons
          .map(path => pathToModelId(repoDir, path) -> path)
          .toMap
      }
    } yield result

  private def pathToModelId(root: Path, path: Path): ModuleId = {
    val relPath = path.subpath(root.nameCount + 1, path.nameCount)
    val moduleName = relPath(0)
    val subModuleName =
      relPath.parent match {
        case Some(parent) =>
          if (parent.filename == Path("codegen-resources")) {
            None
          } else {
            if (parent.filename == moduleName) {
              None
            } else {
              Some(parent.filename)
            }
          }
        case None => None
      }

    ModuleId(moduleName.toString(), subModuleName.map(_.toString()))
  }

  private def loadJson[T](path: Path)(implicit
      classTag: ClassTag[T]
  ): ZIO[Any, Throwable, T] =
    for {
      result <- ZIO.attempt(Jackson.load(classTag.runtimeClass, path.toFile))
    } yield result.asInstanceOf[T]

  private def loadOptionalModel[T](root: Path, name: String, default: => T)(
      implicit classTag: ClassTag[T]
  ): ZIO[Any, Throwable, T] = {
    loadJson[T](root / name).catchSome { case _: FileNotFoundException =>
      ZIO.succeed(default)
    }
  }

  private def loadCustomizationModel(
      root: Path
  ): ZIO[Any, Throwable, CustomizationConfig] =
    loadOptionalModel[CustomizationConfig](
      root,
      "customization.config",
      CustomizationConfig.create()
    )

  private def loadServiceModel(
      root: Path
  ): ZIO[Any, Throwable, ServiceModel] = {
    loadJson[ServiceModel](root / "service-2.json")
  }

  private def loadWaiters(root: Path): ZIO[Any, Throwable, Waiters] =
    loadOptionalModel[Waiters](root, "waiters-2.json", Waiters.none())

  private def loadPaginators(
      root: Path
  ): ZIO[Any, Throwable, Paginators] =
    loadOptionalModel[Paginators](
      root,
      "paginators-1.json",
      Paginators.none()
    )
}
