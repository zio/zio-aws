addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.7")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.1.1")
addSbtPlugin("io.shiftleft" % "sbt-ci-release-early" % "2.0.16")
addSbtPlugin("com.47deg" % "sbt-microsites" % "1.3.3")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.19")
addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.6.3")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("ch.epfl.lamp" % "sbt-dotty" % "0.5.3")

// Codegen project
lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-aws-codegen"), "zio-aws-codegen"))
