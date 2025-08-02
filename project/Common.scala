import sbt.*
import sbt.Keys.*
import zio.aws.codegen.ZioAwsCodegenPlugin.autoImport.*

import scala.collection.JavaConverters.*
import scala.concurrent.duration.*

object Common extends AutoPlugin {

  object autoImport {
    val zioVersion = "2.1.20"
    val zioMockVersion = "1.0.0-RC11"
    val zioCatsInteropVersion = "23.1.0.5"
    val zioReactiveStreamsInteropVersion = "2.0.2"
    val zioConfigVersion = "4.0.4"
    val zioPreludeVersion = "1.0.0-RC41"
    val catsEffectVersion = "3.6.2"

    val awsVersion = "2.32.14"
    val awsSubVersion = awsVersion.drop(awsVersion.indexOf('.') + 1)
    val http4sVersion = "0.23.27"
    val blazeVersion = "0.23.17"
    val fs2Version = "3.12.0"

    val majorVersion = "7"
    val zioAwsVersionPrefix = s"$majorVersion.$awsSubVersion."

    val scala213Version = "2.13.16"
    val scala3Version = "3.3.6"

    val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation")
    val scalacOptions213 = Seq("-deprecation")
    val scalacOptions3 = Seq("-deprecation")
  }

  import autoImport.*

  override val trigger = allRequirements

  override val requires = (ci.release.early.Plugin)

  override lazy val globalSettings =
    Seq(
      commands += Command.command("tagAwsVersion") { state =>
        def log(msg: String) = sLog.value.info(msg)
        adjustTagForAwsVersion(log) match {
          case Some(tagAndVersion) =>
            val tag = tagAndVersion.tag
            ci.release.early.Utils.push(tag, log)
            sLog.value.info(
              "reloading sbt so that sbt-git will set the `version`" +
                s" setting based on the git tag ($tag)"
            )
            "reload" :: state
          case None =>
            sLog.value.info(
              "no need to adjust version to match AWS library version"
            )
            state
        }
      }
    )

  override lazy val projectSettings =
    Seq(
      scalaVersion := scala213Version,
      crossScalaVersions := List(
        scala213Version,
        scala3Version
      ),
      organization := "dev.zio",
      awsLibraryVersion := awsVersion,
      zioLibraryVersion := zioVersion,
      zioMockLibraryVersion := zioMockVersion,
      scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) => scalacOptions212
        case Some((2, 13)) => scalacOptions213
        case Some((3, _))  => scalacOptions3
        case _             => Nil
      }),
      // Publishing
      publishMavenStyle := true,
      description := "Low-level AWS wrapper for ZIO",
      licenses := Seq(
        "APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")
      ),
      developers := List(
        Developer(
          id = "vigoo",
          name = "Daniel Vigovszky",
          email = "daniel.vigovszky@gmail.com",
          url = url("https://vigoo.github.io")
        )
      ),
      ThisBuild / publishTo := {
        // See https://github.com/sbt/sbt/releases/tag/v1.11.0
        val centralSnapshots =
          "https://central.sonatype.com/repository/maven-snapshots/"
        if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
        else localStaging.value
      },
      Global / excludeLintKeys += sonaUploadRequestTimeout, // avoids noisy useless warnings
      sonaUploadRequestTimeout := 12.hours,
      credentials ++=
        (for {
          username <- Option(System.getenv().get("SONATYPE_USERNAME"))
          password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
        } yield Credentials(
          "Central Publisher Portal",
          "central.sonatype.com",
          username,
          password
        )).toSeq,
      resolvers += Resolver.sonatypeCentralSnapshots,
      ci.release.early.Plugin.autoImport.verifyNoSnapshotDependencies := {} // Temporarily disable this check until all dependencies are ready for ZIO 2
    )

  private def adjustTagForAwsVersion(
      log: String => Any
  ): Option[ci.release.early.VersionAndTag] = {
    import ci.release.early.*
    import ci.release.early.Utils.*

    verifyGitIsClean
    val allTags = git.tagList.call.asScala.map(_.getName).toList
    val highestVersion = findHighestVersion(allTags, log)
    log(s"highest version so far: $highestVersion")

    if (
      highestVersion.fold(ifEmpty = false)(_.startsWith(zioAwsVersionPrefix))
    ) {
      // Prefix is already good
      None
    } else {
      val targetVersion = s"${zioAwsVersionPrefix}0"
      val tagName = s"v$targetVersion"
      tag(tagName, log)
      Some(VersionAndTag(targetVersion, tagName))
    }
  }
}
