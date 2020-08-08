
val zioVersion = "1.0.0"
val zioCatsInteropVersion = "2.1.4.0"
val awsVersion = "2.13.69"
val http4sVersion = "0.21.0"
val fs2Version = "2.2.2"

lazy val commonSettings =
  Seq(
    scalaVersion := "2.13.3",
    organization := "io.github.vigoo"
  )

lazy val root = Project("zio-aws", file(".")).settings(commonSettings).settings(
  publishArtifact := false
) aggregate(core, codegen)

lazy val core = Project("zio-aws-core", file("zio-aws-core")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "software.amazon.awssdk" % "core" % awsVersion,
    "dev.zio" %% "zio" % zioVersion,
    "dev.zio" %% "zio-streams" % zioVersion,

    // "software.amazon.awssdk" % "dynamodb" % awsVersion, // TODO: remove
    "software.amazon.awssdk" % "aws-sdk-java" % awsVersion, // TODO: remove
  )
)

lazy val codegen = Project("zio-aws-codegen", file("zio-aws-codegen")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % zioVersion,

    "io.github.vigoo" %% "clipp-core" % "0.4.0",
    "io.github.vigoo" %% "clipp-zio" % "0.4.0",

    "software.amazon.awssdk" % "codegen" % awsVersion,
    "software.amazon.awssdk" % "aws-sdk-java" % awsVersion, // TODO: enable all
    //"software.amazon.awssdk" % "dynamodb" % awsVersion, // TODO: remove
    "org.scalameta" %% "scalameta" % "4.3.20"
  )
)

lazy val http4s = Project("zio-aws-http4s", file("zio-aws-http4s")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "org.http4s" %% "http4s-dsl" % http4sVersion,
    "org.http4s" %% "http4s-blaze-client" % http4sVersion,
    "software.amazon.awssdk" % "http-client-spi" % awsVersion,
    "dev.zio" %% "zio" % zioVersion,
    "dev.zio" %% "zio-interop-cats" % zioCatsInteropVersion,
    "co.fs2" %% "fs2-reactive-streams"% fs2Version
  )
).dependsOn(core)