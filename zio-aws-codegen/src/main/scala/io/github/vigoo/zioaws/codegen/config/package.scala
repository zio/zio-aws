package io.github.vigoo.zioaws.codegen

import java.io.File
import java.nio.file.Path

import zio._
import zio.console._
import io.github.vigoo.clipp._
import io.github.vigoo.clipp.parsers._
import io.github.vigoo.clipp.syntax._
import io.github.vigoo.clipp.zio._

package object config {
  type Config = Has[Config.Service]

  object Config {

    case class Parameters(targetRoot: Path)

    trait Service {
      val parameters: Parameters
    }

  }

  val spec = for {
    _ <- metadata("zio-aws-codegen", "Code generator of the zio-aws modules")
    targetRoot <- namedParameter[File]("Target root directory", "DIR", "target-root")
  } yield Config.Parameters(targetRoot.toPath)

  def fromArgs(args: List[String]): ZLayer[Console, ParserFailure, Config] = {
    val parse: ZIO[Console, ParserFailure, Config.Parameters] = Clipp.parseOrFail[Config.Parameters](args, spec)
    parse.map { p =>
      new Config.Service {
        override val parameters: Config.Parameters = p
      }
    }.toLayer
  }
}
