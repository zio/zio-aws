addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
addSbtPlugin("io.shiftleft" % "sbt-ci-release-early" % "2.1.3")
addSbtPlugin("com.47deg" % "sbt-microsites" % "1.4.4")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.7.1")
addSbtPlugin("com.github.sbt" % "sbt-ghpages" % "0.8.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("dev.zio" % "zio-sbt-website" % "0.2.7")
addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.1.1")

ThisBuild / libraryDependencySchemes += "io.circe" %% "circe-core" % VersionScheme.Always
ThisBuild / libraryDependencySchemes += "com.lihaoyi" %% "geny" % VersionScheme.Always

libraryDependencies += "xalan" % "xalan" % "2.7.3"

// Codegen project
lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-aws-codegen"), "zio-aws-codegen"))

resolvers += Resolver.sonatypeCentralSnapshots

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
