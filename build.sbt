
val zioVersion = "1.0.0"
val awsVersion = "2.13.69"

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