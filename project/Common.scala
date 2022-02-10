import zio.aws.codegen.ZioAwsCodegenPlugin.autoImport._
import sbt._
import Keys._
import com.jsuereth.sbtpgp.PgpKeys._
import xerial.sbt.Sonatype
import xerial.sbt.Sonatype._
import xerial.sbt.Sonatype.SonatypeKeys._

import scala.collection.JavaConverters._

object Common extends AutoPlugin {

  object autoImport {
    val zioVersion = "2.0.0-RC2"
    val zioMockVersion = "1.0.0-RC2"
    val zioCatsInteropVersion = "3.3.0-RC2"
    val zioReactiveStreamsInteropVersion = "2.0.0-RC3"
    val zioConfigVersion = "3.0.0-RC2"
    val zioPreludeVersion = "1.0.0-RC10"
    val catsEffectVersion = "3.3.5"

    val awsVersion = "2.17.127"
    val awsSubVersion = awsVersion.drop(awsVersion.indexOf('.') + 1)
    val http4sVersion = "0.23.10"
    val fs2Version = "3.2.4"

    val majorVersion = "5"
    val zioAwsVersionPrefix = s"$majorVersion.$awsSubVersion."

    val scala212Version = "2.12.15"
    val scala213Version = "2.13.8"
    val scala3Version = "3.1.1"

    val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation")
    val scalacOptions213 = Seq("-deprecation")
    val scalacOptions3 = Seq("-deprecation")
  }

  import autoImport._

  override val trigger = allRequirements

  override val requires = (Sonatype && ci.release.early.Plugin)

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
        scala212Version,
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
      publishTo := sonatypePublishToBundle.value,
      sonatypeTimeoutMillis := 300 * 60 * 1000,
      sonatypeProjectHosting := Some(
        GitHubHosting("zio", "zio-aws", "daniel.vigovszky@gmail.com")
      ),
      sonatypeCredentialHost := "oss.sonatype.org",
      sonatypeRepository := "https://oss.sonatype.org/service/local",
      credentials ++=
        (for {
          username <- Option(System.getenv().get("SONATYPE_USERNAME"))
          password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
        } yield Credentials(
          "Sonatype Nexus Repository Manager",
          "oss.sonatype.org",
          username,
          password
        )).toSeq,
      resolvers +=
        "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",

      ci.release.early.Plugin.autoImport.verifyNoSnapshotDependencies := {} // Temporarily disable this check until all dependencies are ready for ZIO 2
    )

  private def adjustTagForAwsVersion(
      log: String => Any
  ): Option[ci.release.early.VersionAndTag] = {
    import ci.release.early._
    import ci.release.early.Utils._

    verifyGitIsClean
    val allTags = git.tagList.call.asScala.map(_.getName).toList
    val highestVersion = findHighestVersion(allTags, log)
    log(s"highest version so far: $highestVersion")

    if (highestVersion.startsWith(zioAwsVersionPrefix)) {
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
