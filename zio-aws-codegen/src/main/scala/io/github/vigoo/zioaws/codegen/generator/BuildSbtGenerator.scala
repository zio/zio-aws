package io.github.vigoo.zioaws.codegen.generator

import io.github.vigoo.zioaws.codegen.loader.ModelId
import software.amazon.awssdk.core.util.VersionInfo

import scala.meta._

trait BuildSbtGenerator {
  this: HasConfig =>

  protected def generatePluginsSbtCode: String =
    q"""
     addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.4")
     addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.1")
     """.toString.stripPrefix("{").stripSuffix("}")

  protected def generateBuildSbtCode(ids: Set[ModelId]): String = {
    val versionStr = Lit.String(config.parameters.version)
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

      q"""lazy val $nameP = Project($fullNameStr, file($nameStr)).settings(commonSettings).settings(
            libraryDependencies += "software.amazon.awssdk" % $artifactStr % awsVersion,
          ).dependsOn(..$deps)
           """
    }
    val awsVersionStr = Lit.String(VersionInfo.SDK_VERSION)
    val zioVersionStr = Lit.String(config.parameters.zioVersion)
    val zioReactiveStreamsInteropVersionStr = Lit.String(config.parameters.zioInteropReactiveStreamsVersion)

    val code =
      q"""
        import xerial.sbt.Sonatype._
        val awsVersion = $awsVersionStr

        publishArtifact := false

        lazy val commonSettings = Seq(
          scalaVersion := "2.13.3",
          organization := "io.github.vigoo",
          version := $versionStr,
          libraryDependencies ++= Seq(
            "dev.zio" %% "zio" % $zioVersionStr,
            "dev.zio" %% "zio-streams" % $zioVersionStr,
            "dev.zio" %% "zio-interop-reactivestreams" % $zioReactiveStreamsInteropVersionStr
          ),
          // Publishing
          publishMavenStyle := true,
          licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
          publishTo := sonatypePublishTo.value,
          sonatypeProjectHosting := Some(GitHubHosting("vigoo", "zio-aws", "daniel.vigovszky@gmail.com")),
          developers := List(
            Developer(id = "vigoo", name = "Daniel Vigovszky", email = "daniel.vigovszky@gmail.com", url = url("https://vigoo.github.io"))
          )
        )

        lazy val core = Project("zio-aws-core", file("zio-aws-core")).settings(commonSettings).settings(
          libraryDependencies ++= Seq(
            "software.amazon.awssdk" % "aws-core" % awsVersion
          )
        )

        ..$projects
           """
    code.toString.stripPrefix("{").stripSuffix("}")
  }
}
