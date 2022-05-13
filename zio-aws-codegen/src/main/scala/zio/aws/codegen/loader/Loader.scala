package zio.aws.codegen.loader

import software.amazon.awssdk.codegen.C2jModels
import zio._
import zio.nio.file.Path

trait Loader {
  def findModels(): ZIO[Any, Throwable, Set[ModuleId]]
  def loadCodegenModel(id: ModuleId): ZIO[Any, Throwable, C2jModels]
}

object Loader {
  val fromClasspath: ULayer[Loader] = ZLayer.succeed(FromClasspath())
  val fromGit: ZLayer[Any, Nothing, FromGit] =
    ZLayer {
      for {
        map <- Ref.make(Map.empty[ModuleId, Path])
      } yield FromGit(map)
    }

  def loadCodegenModel(
      id: ModuleId
  ): ZIO[Loader, Throwable, C2jModels] =
    ZIO.service[Loader].flatMap(_.loadCodegenModel(id))

  def findModels(): ZIO[Loader, Throwable, Set[ModuleId]] =
    ZIO.service[Loader].flatMap(_.findModels())
}
