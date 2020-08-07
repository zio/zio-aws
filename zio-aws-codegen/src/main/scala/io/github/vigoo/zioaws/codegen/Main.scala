package io.github.vigoo.zioaws.codegen

import java.io.File
import java.nio.file.Path

import io.github.vigoo.clipp.ParserFailure
import io.github.vigoo.clipp.zioapi._
import io.github.vigoo.zioaws.codegen.loader.ModelId
import zio._

object Main extends App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val app = for {
      ids <- loader.findModels()
      _ <- ZIO.foreach(ids) { id =>
        for {
          _ <- console.putStrLn(s"Generating $id")
          model <- loader.loadCodegenModel(id)
          _ <- generator.generateServiceModule(id, model).mapError(error => new RuntimeException(error.toString)) // TODO
        } yield ()
      }
    } yield ExitCode.success

    val cfg = config.fromArgsWithUsageInfo(args, Parameters.spec)
    val modules = loader.live ++ (cfg >>> generator.live)
    app.provideCustomLayer(modules).catchAll {
      case exception: Throwable => console.putStrErr(exception.toString).as(ExitCode.failure)
      case parserFailure: ParserFailure => ZIO.succeed(ExitCode.failure)
    }
  }
}
