package zio.aws.codegen.loader

import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.internal.Jackson
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{Paginators, ServiceModel, Waiters}
import zio.nio.file.{FileSystem, Files, Path}
import zio.{ZIO, ZInputStream}

import java.io.{FileNotFoundException, IOException}
import java.net.{URI, URL}
import java.nio.file.FileSystemNotFoundException
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

case class FromClasspath() extends Loader {
  private def findRoot(
      name: String,
      subModule: Option[String]
  ): ZIO[Any, Throwable, URL] =
    ZIO
      .attempt {
        val urls = getClass.getClassLoader
          .getResources("codegen-resources")
          .asScala
          .toList
        urls.find(_.getPath.contains(s"/$name-"))
      }
      .flatMap {
        case Some(url) =>
          subModule match {
            case Some(value) =>
              ZIO.succeed(new URL(url.toString + "/" + value))
            case None => ZIO.succeed(url)
          }
        case None => ZIO.fail(new RuntimeException(s"Could not find $name"))
      }

  private def loadJson[T](url: URL)(implicit
      classTag: ClassTag[T]
  ): ZIO[Any, Throwable, T] =
    ZIO.scoped { 
      for {
        tempPath <- Files.createTempFileScoped(".json", None, Seq())
        inputStream <- ZIO.attempt(url.openStream())
        bytes <-
          ZInputStream
            .fromInputStream(inputStream)
            .readAll(4096)
            .flattenErrorOption(new IOException(s"Cannot read from $url"))
        _ <- Files.writeBytes(tempPath, bytes)
        result <-
          ZIO.attempt(Jackson.load(classTag.runtimeClass, tempPath.toFile))
      } yield result.asInstanceOf[T]
    }

  private def loadOptionalModel[T](root: URL, name: String, default: => T)(
      implicit classTag: ClassTag[T]
  ): ZIO[Any, Throwable, T] = {
    val url = new URL(s"${root.toString}/$name")

    loadJson[T](url).catchSome { case _: FileNotFoundException =>
      ZIO.succeed(default)
    }
  }

  private def loadCustomizationModel(
      root: URL
  ): ZIO[Any, Throwable, CustomizationConfig] =
    loadOptionalModel[CustomizationConfig](
      root,
      "customization.config",
      CustomizationConfig.create()
    )

  private def loadServiceModel(
      root: URL
  ): ZIO[Any, Throwable, ServiceModel] = {
    val url = new URL(root.toString + "/service-2.json")
    loadJson[ServiceModel](url)
  }

  private def loadWaiters(root: URL): ZIO[Any, Throwable, Waiters] =
    loadOptionalModel[Waiters](root, "waiters-2.json", Waiters.none())

  private def loadPaginators(
      root: URL
  ): ZIO[Any, Throwable, Paginators] =
    loadOptionalModel[Paginators](
      root,
      "paginators-1.json",
      Paginators.none()
    )

  private val serviceNameRegex = """.+/([a-z0-9]+)\-.+\.jar!.*""".r

  private def findServiceSpecification(
      uri: URI,
      root: Path
  ): ZIO[Any, Throwable, Set[ModuleId]] = {
    val name = uri.toString match {
      case serviceNameRegex(name) => name
      case _                      => uri.toString
    }

    Files
      .walk(root)
      .filter(_.filename.toString() == "service-2.json")
      .map { file =>
        if (file.parent.contains(root)) {
          ModuleId(name, None)
        } else {
          ModuleId(name, file.parent.map(parent => parent.filename.toString))
        }
      }
      .runCollect
      .map(_.toSet)
  }

  override def findModels(): ZIO[Any, Throwable, Set[ModuleId]] =
    for {
      codegenRootUrls <- ZIO.attempt(
        getClass.getClassLoader
          .getResources("codegen-resources")
          .asScala
          .toList
      )
      result <- ZIO.foreach(codegenRootUrls) { url =>
        val uri = url.toURI
        if (uri.getScheme == "jar") {
          ZIO.scoped {
            FileSystem
              .getFileSystem(uri)
              .absorb
              .catchSome { case _: FileSystemNotFoundException =>
                FileSystem.newFileSystem(uri)
              }
              .flatMap { fs =>
                findServiceSpecification(
                  uri,
                  fs.getPath("/codegen-resources")
                )
              }
          }
        } else {
          findServiceSpecification(uri, Path(uri))
        }
      }
    } yield result.fold(Set.empty)(_ union _)

  override def loadCodegenModel(
      id: ModuleId
  ): ZIO[Any, Throwable, C2jModels] =
    for {
      root <- findRoot(id.name, id.subModule)
      customizationConfig <- loadCustomizationModel(root)
      serviceModel <- loadServiceModel(root)
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
}
