val zioVersion = "2.0.0-RC2"
val awsVersion = "2.17.171"

sbtPlugin := true
scalaVersion := "2.12.15"
organization := "io.github.vigoo"
scalacOptions := Seq("-Ypartial-unification", "-deprecation")
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-nio" % "2.0.0-RC2",
  "io.circe" %% "circe-yaml" % "0.14.1",
  "software.amazon.awssdk" % "codegen" % awsVersion,
  "org.scalameta" %% "scalameta" % "4.4.31",
  "com.lihaoyi" %% "os-lib" % "0.8.0",
  "io.github.vigoo" %% "metagen-core" % "0.0.14",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "6.0.0.202111291000-r"
)
