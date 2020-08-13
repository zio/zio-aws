package io.github.vigoo.zioaws.codegen

import java.io.{FileNotFoundException, IOException}
import java.net.URL
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileSystem, FileSystemNotFoundException, FileSystems, FileVisitResult, FileVisitor, Files, Path, Paths, SimpleFileVisitor}

import io.github.vigoo.zioaws.codegen.Main.getClass

import scala.jdk.CollectionConverters._
import software.amazon.awssdk.codegen.C2jModels
import software.amazon.awssdk.codegen.internal.Jackson
import software.amazon.awssdk.codegen.model.config.customization.CustomizationConfig
import software.amazon.awssdk.codegen.model.intermediate.ServiceExamples
import software.amazon.awssdk.codegen.model.service.{Paginators, ServiceModel, Waiters}
import zio._

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.matching.Regex

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
        case None => name
      }
  }

  object Loader {
    trait Service {
      def findModels(): ZIO[Any, Throwable, Set[ModelId]]
      def loadCodegenModel(id: ModelId): ZIO[Any, Throwable, C2jModels]
    }
  }

  val live: ULayer[Loader] = ZLayer.succeed {
    new Loader.Service {
      private def findRoot(name: String, subModule: Option[String]): ZIO[Any, Throwable, URL] =
        ZIO {
          val urls = getClass.getClassLoader.getResources("codegen-resources").asScala.toList
          urls.find(_.getPath.contains(s"/$name-"))
        }.flatMap {
          case Some(url) =>
            subModule match {
              case Some(value) => ZIO.succeed(new URL(url.toString + "/" + value))
              case None => ZIO.succeed(url)
            }
          case None => ZIO.fail(new RuntimeException(s"Could not find $name"))
        }

      private def loadOptionalModel[T](root: URL, name: String, default: => T)
                                      (implicit classTag: ClassTag[T]): Task[T] = {
        val url = new URL(s"${root.toString}/$name")
        ZIO(
          Jackson.load(classTag.runtimeClass, url.openStream())
        ).map(_.asInstanceOf[T]).catchSome {
          case _: FileNotFoundException => ZIO.succeed(default)
        }
      }

      private def loadCustomizationModel(root: URL): Task[CustomizationConfig] =
        loadOptionalModel[CustomizationConfig](root, "customization.config", CustomizationConfig.create())

      private def loadServiceModel(root: URL): Task[ServiceModel] = {
        val url = new URL(root.toString + "/service-2.json")
        ZIO(Jackson.load(classOf[ServiceModel], url.openStream()))
      }

      private def loadWaiters(root: URL): Task[Waiters] =
        loadOptionalModel[Waiters](root, "waiters-2.json", Waiters.none())

      private def loadPaginators(root: URL): Task[Paginators] =
        loadOptionalModel[Paginators](root, "paginators-1.json", Paginators.none())

      private def loadServiceExamples(root: URL): Task[ServiceExamples] =
        loadOptionalModel[ServiceExamples](root, "examples-1.json", ServiceExamples.none())

      private val serviceNameRegex = """.+/([a-z0-9]+)\-.+\.jar!.*""".r

      override def findModels(): ZIO[Any, Throwable, Set[ModelId]] =
        ZIO {
          val codegenRootUrls = getClass.getClassLoader.getResources("codegen-resources").asScala.toList
          val codegenRootPaths = codegenRootUrls.map { url =>
            val uri = url.toURI
            if (uri.getScheme == "jar") {
              val fs = Try(FileSystems.getFileSystem(uri)).recover { case _: FileSystemNotFoundException => FileSystems.newFileSystem(uri, Map.empty[String, Any].asJava) }.get
              uri -> fs.getPath("/codegen-resources")
            } else {
              uri -> Paths.get(uri)
            }
          }
          val result = new ListBuffer[ModelId]
          for ((uri, root) <- codegenRootPaths) {
            val name = uri.toString match {
              case serviceNameRegex(name) => name
              case _ => uri.toString
            }

            Files.walkFileTree(root, new SimpleFileVisitor[Path] {
              override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
                if (file.getFileName.toString == "service-2.json") {
                  if (file.getParent == root) {
                    result += ModelId(name, None)
                  } else {
                    result += ModelId(name, Some(file.getParent.getFileName.toString))
                  }
                }
                FileVisitResult.CONTINUE
              }
            })
          }
          result.toSet
        }

      override def loadCodegenModel(id: ModelId): ZIO[Any, Throwable, C2jModels] =
        for {
          root <- findRoot(id.name, id.subModule)
          customizationConfig <- loadCustomizationModel(root)
          serviceModel <- loadServiceModel(root)
          waiters <- loadWaiters(root)
          paginators <- loadPaginators(root)
          examples <- loadServiceExamples(root)
          model <- ZIO {
            C2jModels.builder()
              .customizationConfig(customizationConfig)
              .serviceModel(serviceModel)
              .waitersModel(waiters)
              .paginatorsModel(paginators)
              .examplesModel(examples)
              .build()
          }
        } yield model
    }
  }

  def loadCodegenModel(id: ModelId): ZIO[Loader, Throwable, C2jModels] =
    ZIO.service[Loader.Service].flatMap(_.loadCodegenModel(id))

  def findModels(): ZIO[Loader, Throwable, Set[ModelId]] =
    ZIO.service[Loader.Service].flatMap(_.findModels())
}
