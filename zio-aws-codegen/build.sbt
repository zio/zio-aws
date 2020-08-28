val zioVersion = "1.0.1"
val awsVersion = "2.14.3"

sbtPlugin := true
organization := "io.github.vigoo"
scalacOptions := Seq("-Ypartial-unification", "-deprecation")
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,

  "io.circe" %% "circe-yaml" % "0.13.1",

  "software.amazon.awssdk" % "codegen" % awsVersion,
  "software.amazon.awssdk" % "aws-sdk-java" % awsVersion,

  "org.scalameta" %% "scalameta" % "4.3.21",
  "com.lihaoyi" %% "os-lib" % "0.7.1"
)
