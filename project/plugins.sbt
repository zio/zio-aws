addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.4")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.1")
addSbtPlugin("io.shiftleft" % "sbt-ci-release-early" % "1.2.1")

// Codegen project

val zioVersion = "1.0.1"
val awsVersion = "2.14.3"

lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-aws-codegen"), "zio-aws-codegen"))
