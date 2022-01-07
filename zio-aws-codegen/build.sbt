val zioVersion = "1.0.13"
val awsVersion = "2.17.106"

sbtPlugin := true
scalaVersion := "2.12.15"
organization := "io.github.vigoo"
scalacOptions := Seq("-Ypartial-unification", "-deprecation")
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-nio" % "1.0.0-RC12",
  "io.circe" %% "circe-yaml" % "0.14.1",
  "software.amazon.awssdk" % "codegen" % awsVersion,
  "software.amazon.awssdk" % "aws-sdk-java" % awsVersion,
  "org.scalameta" %% "scalameta" % "4.4.31",
  "com.lihaoyi" %% "os-lib" % "0.8.0",
  "io.github.vigoo" %% "metagen-core" % "0.0.12"
)
