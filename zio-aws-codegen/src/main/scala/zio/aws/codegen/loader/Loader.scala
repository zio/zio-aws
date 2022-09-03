package zio.aws.codegen.loader

import software.amazon.awssdk.codegen.C2jModels
import zio._
import zio.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

trait Loader {
  def findModels(): ZIO[Any, Throwable, Set[ModuleId]]
  def loadCodegenModel(id: ModuleId): ZIO[Any, Throwable, C2jModels]
}

object Loader {
  val fromClasspath: ULayer[Loader] = ZLayer.succeed(FromClasspath())
  val fromGit: ZLayer[Any, Nothing, Loader] =
    ZLayer {
      for {
        map <- Ref.make(Map.empty[ModuleId, Path])
        semaphore <- Semaphore.make(1)
      } yield FromGit(map, semaphore)
    }

  private val cachedLoader: AtomicReference[Loader] = new AtomicReference(null)
  def cached[R, E <: Throwable](
      impl: ZLayer[R, E, Loader]
  ): ZLayer[R, Throwable, Loader] =
    ZLayer {
      for {
        runtime <- ZIO.runtime[R]
        value <- ZIO.attempt {
          cachedLoader.updateAndGet { (cachedLoader: Loader) =>
            if (cachedLoader == null) {
              Unsafe.unsafe { implicit u =>
                runtime.unsafe
                  .run {
                    impl.build(Scope.global)
                  }
                  .getOrThrowFiberFailure()
                  .get
              }
            } else {
              cachedLoader
            }
          }
        }
      } yield value
    }

  def loadCodegenModel(
      id: ModuleId
  ): ZIO[Loader, Throwable, C2jModels] =
    ZIO.service[Loader].flatMap(_.loadCodegenModel(id))

  def findModels(): ZIO[Loader, Throwable, Set[ModuleId]] =
    ZIO.service[Loader].flatMap(_.findModels())
}
