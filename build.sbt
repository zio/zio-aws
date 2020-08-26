import Common._
import Core._

enablePlugins(ZioAwsCodegenPlugin)

lazy val core = Project("zio-aws-core", file("zio-aws-core")).settings(commonSettings).settings(coreSettings)

lazy val root = Project("zio-aws", file(".")).settings(commonSettings).settings(
  publishArtifact := false,
) aggregate(core, http4s, netty, akkahttp)

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
