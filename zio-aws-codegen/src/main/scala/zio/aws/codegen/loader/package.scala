package zio.aws.codegen

import java.io.{FileNotFoundException, IOException}
import java.net.{URI, URL}
import java.nio.file.FileSystemNotFoundException

import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.internal.Jackson
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.service.{
  Paginators,
  ServiceModel,
  Waiters
}
import zio._
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.{FileSystem, Files}

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

package object loader {
  type Loader = Has[Loader.Service]

  case class ModelId(name: String, subModule: Option[String]) {
    val moduleName: String = subModule.getOrElse(name)

    val subModuleName: Option[String] = subModule.flatMap { s =>
      val stripped = s.stripPrefix(name)
      if (stripped.isEmpty) None else Some(stripped)
    }

    override def toString: String =
      subModule match {
        case Some(value) => s"$name:$value"
        case None        => name
      }
  }

  object ModelId {
    def parse(value: String): Either[String, ModelId] =
      value.split(':').toList match {
        case List(single)    => Right(ModelId(single, None))
        case List(main, sub) => Right(ModelId(main, Some(sub)))
        case _ =>
          Left(
            s"Failed to parse $value as ModelId, use 'svc' or 'svc:subsvc' syntax."
          )
      }
  }

  object Loader {

    trait Service {
      def findModels(): ZIO[Blocking, Throwable, Set[ModelId]]

      def loadCodegenModel(id: ModelId): ZIO[Blocking, Throwable, C2jModels]
    }

  }

  val live: ULayer[Loader] = ZLayer.succeed {
    new Loader.Service {
      private def findRoot(
          name: String,
          subModule: Option[String]
      ): ZIO[Any, Throwable, URL] =
        ZIO
          .effect {
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
      ): ZIO[Blocking, Throwable, T] =
        ZManaged
          .make(
            Files.createTempFile(".json", None, Seq())
          )(path => ZIO.effect(Files.delete(path)).orDie)
          .use { tempPath =>
            for {
              inputStream <- ZIO.effect(url.openStream())
              bytes <-
                ZInputStream
                  .fromInputStream(inputStream)
                  .readAll(4096)
                  .flattenErrorOption(new IOException(s"Cannot read from $url"))
              _ <- Files.writeBytes(tempPath, bytes)
              result <-
                ZIO.effect(Jackson.load(classTag.runtimeClass, tempPath.toFile))
            } yield result.asInstanceOf[T]
          }

      private def loadOptionalModel[T](root: URL, name: String, default: => T)(
          implicit classTag: ClassTag[T]
      ): ZIO[Blocking, Throwable, T] = {
        val url = new URL(s"${root.toString}/$name")

        loadJson[T](url).catchSome { case _: FileNotFoundException =>
          ZIO.succeed(default)
        }
      }

      private def loadCustomizationModel(
          root: URL
      ): ZIO[Blocking, Throwable, CustomizationConfig] =
        loadOptionalModel[CustomizationConfig](
          root,
          "customization.config",
          CustomizationConfig.create()
        )

      private def loadServiceModel(
          root: URL
      ): ZIO[Blocking, Throwable, ServiceModel] = {
        val url = new URL(root.toString + "/service-2.json")
        loadJson[ServiceModel](url)
      }

      private def loadWaiters(root: URL): ZIO[Blocking, Throwable, Waiters] =
        loadOptionalModel[Waiters](root, "waiters-2.json", Waiters.none())

      private def loadPaginators(
          root: URL
      ): ZIO[Blocking, Throwable, Paginators] =
        loadOptionalModel[Paginators](
          root,
          "paginators-1.json",
          Paginators.none()
        )

      private val serviceNameRegex = """.+/([a-z0-9]+)\-.+\.jar!.*""".r

      private def findServiceSpecification(
          uri: URI,
          root: Path
      ): ZIO[Blocking, Throwable, Set[ModelId]] = {
        val name = uri.toString match {
          case serviceNameRegex(name) => name
          case _                      => uri.toString
        }

        Files
          .walk(root)
          .filter(_.filename.toString() == "service-2.json")
          .map { file =>
            if (file.parent.contains(root)) {
              ModelId(name, None)
            } else {
              ModelId(name, file.parent.map(parent => parent.filename.toString))
            }
          }
          .runCollect
          .map(_.toSet)
      }

      override def findModels(): ZIO[Blocking, Throwable, Set[ModelId]] =
        for {
          codegenRootUrls <- ZIO.effect(
            getClass.getClassLoader
              .getResources("codegen-resources")
              .asScala
              .toList
          )
          result <- ZIO.foreach(codegenRootUrls) { url =>
            val uri = url.toURI
            if (uri.getScheme == "jar") {
              FileSystem
                .getFileSystem(uri)
                .catchSome { case _: FileSystemNotFoundException =>
                  FileSystem.newFileSystem(uri)
                }
                .use { fs =>
                  findServiceSpecification(
                    uri,
                    fs.getPath("/codegen-resources")
                  )
                }
            } else {
              findServiceSpecification(uri, Path(uri))
            }
          }
        } yield result.fold(Set.empty)(_ union _)

      override def loadCodegenModel(
          id: ModelId
      ): ZIO[Blocking, Throwable, C2jModels] =
        for {
          root <- findRoot(id.name, id.subModule)
          customizationConfig <- loadCustomizationModel(root)
          serviceModel <- loadServiceModel(root)
          waiters <- loadWaiters(root)
          paginators <- loadPaginators(root)
          model <- ZIO {
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
  }

  def loadCodegenModel(
      id: ModelId
  ): ZIO[Loader with Blocking, Throwable, C2jModels] =
    ZIO.service[Loader.Service].flatMap(_.loadCodegenModel(id))

  def findModels(): ZIO[Loader with Blocking, Throwable, Set[ModelId]] =
    ZIO.service[Loader.Service].flatMap(_.findModels())
}
