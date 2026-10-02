import com.jsuereth.sbtpgp.PgpKeys.{pgpPublicRing, pgpSecretRing}
import microsites.ConfigYml
import zio.sbt.githubactions.{Branch, DependencyBot, Trigger}
import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

Global / onChangedBuildSource := ReloadOnSourceChanges

enablePlugins(Common, ZioAwsCodegenPlugin, GitVersioning)

ThisBuild / ciParallelJobs := 5
ThisBuild / ciSeparateJobs := Seq("zio-aws-ec2")

// Everything in ci.yml is hand-built from the module list; the plugin's stock jobs and the extras it
// wraps around them are switched off.
ThisBuild / ciBuildJobs := zio.aws.codegen.ci.ZioAwsCi.buildJobs(
  zio.aws.codegen.ZioAwsCodegenPlugin.moduleNames,
  ciParallelJobs.value,
  ciSeparateJobs.value.toSet
)
ThisBuild / ciReleaseJobs := zio.aws.codegen.ci.ZioAwsCi.releaseJobs(
  zio.aws.codegen.ZioAwsCodegenPlugin.moduleNames,
  ciParallelJobs.value,
  ciSeparateJobs.value.toSet
)
ThisBuild / ciLintJobs := Seq.empty
ThisBuild / ciTestJobs := Seq.empty
ThisBuild / ciUpdateReadmeJobs := Seq.empty
ThisBuild / ciPostReleaseJobs := Seq.empty
ThisBuild / ciReportSuccessfulJobs := Seq.empty
ThisBuild / ciWorkflowPermissions := None
ThisBuild / ciConcurrency := None
ThisBuild / ciEnableReleaseDrafter := false
// Scala Steward runs as the zio-scala-steward GitHub App, so its PRs come from a bot account that
// auto-approve/auto-merge can recognise (they were previously opened by a personal token).
ThisBuild / ciEnableScalaSteward := true
ThisBuild / ciScalaStewardSchedule := "0 21 * * *"
ThisBuild / ciScalaStewardWorkflowEnv := Map("JAVA_OPTS" -> "-Xmx6g -Xms1g")
ThisBuild / ciDependencyUpdateBots := Seq(
  DependencyBot.Dependabot,
  DependencyBot.Renovate,
  DependencyBot.ScalaSteward("zio-scala-steward")
)
ThisBuild / ciEnableDependabot := false
ThisBuild / ciEnableRegenerateWorkflows := false
ThisBuild / ciWorkflowEnv := Map.empty
ThisBuild / ciWorkflowTriggers := Seq(
  Trigger.PullRequest(ignoredBranches = Seq(Branch.Named("gh-pages"))),
  Trigger.Push(branches = Seq(Branch.Named("master")))
)
ThisBuild / artifactListTarget := file("docs/artifacts.md")
ThisBuild / versionScheme := Some(VersionScheme.PVP)

Global / pgpPublicRing := file("/tmp/public.asc")
Global / pgpSecretRing := file("/tmp/secret.asc")
Global / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray())

lazy val root = Project("zio-aws", file(".")).settings(
  publishArtifact := false
) aggregate (core, http4s, netty, akkahttp, docs)

lazy val core = Project("zio-aws-core", file("zio-aws-core"))
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "aws-core" % awsVersion,
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-interop-reactivestreams" % zioReactiveStreamsInteropVersion,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0",
      "dev.zio" %% "zio-test" % zioVersion % "test",
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Compile / doc / sources := Seq.empty // Broken on Scala 3
  )

lazy val http4s = Project("zio-aws-http4s", file("zio-aws-http4s"))
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-blaze-client" % blazeVersion,
      "software.amazon.awssdk" % "http-client-spi" % awsVersion,
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-interop-cats" % zioCatsInteropVersion,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2"
    )
  )
  .dependsOn(core)

lazy val akkahttp = Project("zio-aws-akka-http", file("zio-aws-akka-http"))
  .settings(
    libraryDependencies ++= Seq(
      ("com.typesafe.akka" %% "akka-stream" % "2.6.19")
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang.modules", "scala-collection-compat_2.13"),
      ("com.typesafe.akka" %% "akka-http" % "10.2.10")
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang.modules", "scala-collection-compat_2.13"),
      ("com.github.matsluni" %% "aws-spi-akka-http" % "1.0.1")
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
    )
  )
  .dependsOn(core)

lazy val netty = Project("zio-aws-netty", file("zio-aws-netty"))
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "netty-nio-client" % awsVersion
    )
  )
  .dependsOn(core)

lazy val crthttp = Project("zio-aws-crt-http", file("zio-aws-crt-http"))
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "aws-crt-client" % awsVersion
    )
  )
  .dependsOn(core)

lazy val examples = Project("examples", file("examples")).settings(
  publishArtifact := false
) aggregate (
  example1,
//  example2,
  example3
)

lazy val example1 = Project("example1", file("examples") / "example1")
  .dependsOn(
    core,
    http4s,
    netty,
    LocalProject("zio-aws-elasticbeanstalk"),
    LocalProject("zio-aws-ec2")
  )

lazy val example2 = Project("example2", file("examples") / "example2")
  .settings(
    resolvers += Resolver.jcenterRepo,
    libraryDependencies ++= Seq(
      "nl.vroste" %% "rezilience" % "0.9.2"
    )
  )
  .dependsOn(
    core,
    netty,
    LocalProject("zio-aws-dynamodb")
  )

lazy val example3 = Project("example3", file("examples") / "example3")
  .dependsOn(
    core,
    http4s,
    netty,
    LocalProject("zio-aws-kinesis")
  )

lazy val integtests = Project("integtests", file("integtests"))
  .settings(
    crossScalaVersions := List(scala213Version),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-test" % zioVersion,
      "dev.zio" %% "zio-test-sbt" % zioVersion,
      "org.apache.logging.log4j" % "log4j-1.2-api" % "2.17.1",
      "org.apache.logging.log4j" % "log4j-core" % "2.17.1",
      "org.apache.logging.log4j" % "log4j-api" % "2.17.1",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.17.1"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / parallelExecution := false,
    evictionErrorLevel := Level.Info
  )
  .dependsOn(
    core,
    http4s,
    netty,
    akkahttp,
    crthttp,
    LocalProject("zio-aws-s3"),
    LocalProject("zio-aws-dynamodb")
  )

lazy val docs = project
  .in(file("zio-aws-docs"))
  .settings(
    publish / skip := true,
    moduleName := "zio-aws-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName := "ZIO AWS",
    mainModuleName := (core / moduleName).value,
    projectStage := ProjectStage.ProductionReady,
    docsVersioningScheme := zio.sbt.WebsitePlugin.VersioningScheme.HashVersioning,
    libraryDependencies ++= Seq("dev.zio" %% "zio-config" % zioConfigVersion)
  )
  .dependsOn(
    core,
    http4s,
    netty,
    akkahttp,
    crthttp,
    LocalProject("zio-aws-elasticbeanstalk"),
    LocalProject("zio-aws-ec2")
  )
  .enablePlugins(WebsitePlugin)
