addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.5")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.1")
addSbtPlugin("io.shiftleft" % "sbt-ci-release-early" % "1.2.4")
addSbtPlugin("com.47deg" % "sbt-microsites" % "1.2.1")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.12")
addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.6.3")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")

// Codegen project

val zioVersion = "1.0.1"
val awsVersion = "2.14.3"

lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-aws-codegen"), "zio-aws-codegen"))
