package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.core.util.VersionInfo

import scala.meta._

trait SbtGenerator {
  this: HasConfig =>

  protected def generateSubprojectsSbtCode(ids: Set[ModelId]): String = {
    val projects = ids.toList.map { id =>
      val name = Term.Name(id.moduleName)
      val nameStr = Lit.String(id.moduleName)
      val fullNameStr = Lit.String(s"zio-aws-${id.moduleName}")
      val artifactStr = Lit.String(id.name)
      val nameP = Pat.Var(name)

      val deps =
        id.subModule match {
          case Some(value) if value != id.name =>
            val baseProject = Term.Name(id.name)
            List(Term.Name("core"), baseProject)
          case _ =>
            List(Term.Name("core"))
        }

      val modelIdLit = Lit.String(id.toString)

      q"""lazy val $nameP = Project($fullNameStr, file("generated") / $nameStr).settings(commonSettings).settings(
            libraryDependencies += "software.amazon.awssdk" % $artifactStr % awsVersion,
            awsLibraryId := $modelIdLit,
            Compile / sourceGenerators += generateSourcesTask.taskValue
          ).dependsOn(..$deps)
           """
    }

    val code =
      q"""
        import Common._
        import Core._
        lazy val core = Project("zio-aws-core", file("zio-aws-core")).settings(commonSettings).settings(coreSettings)

        ..$projects
       """
    code.toString.stripPrefix("{").stripSuffix("}")
  }
}
