addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.15")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1")
addSbtPlugin("io.shiftleft" % "sbt-ci-release-early" % "2.0.37")
addSbtPlugin("com.47deg" % "sbt-microsites" % "1.4.1")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.6")
addSbtPlugin("com.github.sbt" % "sbt-ghpages" % "0.7.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("dev.zio" % "zio-sbt-website" % "0.3.4")

ThisBuild / libraryDependencySchemes += "io.circe" %% "circe-core" % VersionScheme.Always
ThisBuild / libraryDependencySchemes += "com.lihaoyi" %% "geny" % VersionScheme.Always

libraryDependencies += "xalan" % "xalan" % "2.7.2"

// Codegen project
lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-aws-codegen"), "zio-aws-codegen"))

resolvers += Resolver.sonatypeRepo("public")

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
