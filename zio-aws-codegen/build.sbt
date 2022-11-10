val zioVersion = "2.0.3"
val awsVersion = "2.18.14"

sbtPlugin := true
scalaVersion := "2.12.16"
organization := "io.github.vigoo"
scalacOptions := Seq("-Ypartial-unification", "-deprecation")
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-nio" % "2.0.0",
  "io.circe" %% "circe-yaml" % "0.14.1",
  "software.amazon.awssdk" % "codegen" % awsVersion,
  "org.scalameta" %% "scalameta" % "4.4.31",
  "com.lihaoyi" %% "os-lib" % "0.8.0",
  "io.github.vigoo" %% "metagen-core" % "0.0.17",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "6.0.0.202111291000-r"
)
