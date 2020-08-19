import xerial.sbt.Sonatype._

val zioVersion = "1.0.0"
val zioCatsInteropVersion = "2.1.4.0"
val zioReactiveStreamsInteropVersion = "1.0.3.5"
val catsEffectVersion = "2.1.4"

val awsVersion = "2.13.76"
val awsSubVersion = awsVersion.drop(awsVersion.indexOf('.') + 1)
val http4sVersion = "0.21.7"
val fs2Version = "2.2.2"

val majorVersion = "1"
val minorVersion = "1"
val zioAwsVersion = s"$majorVersion.$awsSubVersion.$minorVersion"

val generateAll = taskKey[Unit]("Generates all AWS client libraries")
val buildAll = taskKey[Unit]("Generates and builds all AWS client libraries")
val publishLocalAll = taskKey[Unit]("Generates, builds and publishes all AWS client libraries")

val scala212Version = "2.12.12"
val scala213Version = "2.13.3"

val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation")
val scalacOptions213 = Seq("-deprecation")

lazy val commonSettings =
  Seq(
    scalaVersion := scala213Version,
    crossScalaVersions := List(scala212Version, scala213Version),

    organization := "io.github.vigoo",
    version := zioAwsVersion,

    scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12)) => scalacOptions212
      case Some((2, 13)) => scalacOptions213
      case _ => Nil
    }),

      // Publishing
    publishMavenStyle := true,
    licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    publishTo := sonatypePublishTo.value,
    sonatypeProjectHosting := Some(GitHubHosting("vigoo", "zio-aws", "daniel.vigovszky@gmail.com")),
    developers := List(
      Developer(id = "vigoo", name = "Daniel Vigovszky", email = "daniel.vigovszky@gmail.com", url = url("https://vigoo.github.io"))
    ),

    credentials ++=
      (for {
        username <- Option(System.getenv().get("SONATYPE_USERNAME"))
        password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
      } yield
        Credentials(
          "Sonatype Nexus Repository Manager",
          "oss.sonatype.org",
          username,
          password)).toSeq,

  )

lazy val root = Project("zio-aws", file(".")).settings(commonSettings).settings(
  publishArtifact := false,
  generateAll := Def.taskDyn {
    val root = baseDirectory.value.getAbsolutePath
    val scalaVer = scalaVersion.value
    Def.task {
      (codegen / Compile / run).toTask(s" --target-root ${root}/generated --source-root ${root} --version $zioAwsVersion --scala-version $scalaVer --zio-version $zioVersion --zio-rs-version $zioReactiveStreamsInteropVersion").value
    }
  }.value,
  buildAll := Def.taskDyn {
    val _ = generateAll.value
    val generatedRoot = baseDirectory.value / "generated"
    val launcherVersion = sbtVersion.value
    val launcher = s"sbt-launch-$launcherVersion.jar"
    val launcherFile = generatedRoot / launcher

    Def.task[Unit] {
      if (!launcherFile.exists) {
        val u = url(s"https://oss.sonatype.org/content/repositories/public/org/scala-sbt/sbt-launch/$launcherVersion/sbt-launch-$launcherVersion.jar")
        sbt.io.Using.urlInputStream(u) { inputStream =>
          IO.transfer(inputStream, launcherFile)
        }
      }
      val fork = new ForkRun(ForkOptions()
        .withWorkingDirectory(generatedRoot))
      fork.run(
        "xsbt.boot.Boot",
        classpath = launcherFile :: Nil,
        options = "compile" :: Nil,
        log = streams.value.log
      )
    }
  }.value,
  publishLocalAll := Def.taskDyn {
    val _ = generateAll.value
    val generatedRoot = baseDirectory.value / "generated"
    val launcherVersion = sbtVersion.value
    val launcher = s"sbt-launch-$launcherVersion.jar"
    val launcherFile = generatedRoot / launcher

    Def.task[Unit] {
      if (!launcherFile.exists) {
        val u = url(s"https://oss.sonatype.org/content/repositories/public/org/scala-sbt/sbt-launch/$launcherVersion/sbt-launch-$launcherVersion.jar")
        sbt.io.Using.urlInputStream(u) { inputStream =>
          IO.transfer(inputStream, launcherFile)
        }
      }
      val fork = new ForkRun(ForkOptions()
        .withWorkingDirectory(generatedRoot))
      fork.run(
        "xsbt.boot.Boot",
        classpath = launcherFile :: Nil,
        options = "set publishArtifact in (ThisBuild, Compile, packageDoc) := false" :: "publishLocal" :: Nil,
        log = streams.value.log
      )
    }
  }.value
) aggregate(core, codegen, http4s, netty, akkahttp)

lazy val core = Project("zio-aws-core", file("zio-aws-core")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "software.amazon.awssdk" % "aws-core" % awsVersion,
    "dev.zio" %% "zio" % zioVersion,
    "dev.zio" %% "zio-streams" % zioVersion,
    "dev.zio" %% "zio-interop-reactivestreams" % zioReactiveStreamsInteropVersion,
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.6",

    "dev.zio" %% "zio-test" % zioVersion % "test",
    "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val codegen = Project("zio-aws-codegen", file("zio-aws-codegen")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % zioVersion,

    "io.github.vigoo" %% "clipp-core" % "0.4.0",
    "io.github.vigoo" %% "clipp-zio" % "0.4.0",

    "software.amazon.awssdk" % "codegen" % awsVersion,
    "software.amazon.awssdk" % "aws-sdk-java" % awsVersion,

    "org.scalameta" %% "scalameta" % "4.3.20",
    "com.lihaoyi" %% "os-lib" % "0.7.1"
  )
)

lazy val http4s = Project("zio-aws-http4s", file("zio-aws-http4s")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "org.http4s" %% "http4s-dsl" % http4sVersion,
    "org.http4s" %% "http4s-blaze-client" % http4sVersion,
    "software.amazon.awssdk" % "http-client-spi" % awsVersion,
    "dev.zio" %% "zio" % zioVersion,
    "dev.zio" %% "zio-interop-cats" % zioCatsInteropVersion,
    "co.fs2" %% "fs2-reactive-streams" % fs2Version,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1",
  )
).dependsOn(core)

lazy val akkahttp = Project("zio-aws-akka-http", file("zio-aws-akka-http")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-stream" % "2.6.8",
    "com.typesafe.akka" %% "akka-http" % "10.2.0",
    "com.github.matsluni" %% "aws-spi-akka-http" % "0.0.9",
  )
).dependsOn(core)

lazy val netty = Project("zio-aws-netty", file("zio-aws-netty")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "software.amazon.awssdk" % "netty-nio-client" % awsVersion,
  )
).dependsOn(core)
