import sbt._
import Keys._
import Common._

object Core {
  val coreSettings = Seq(

    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "aws-core" % awsVersion,
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-interop-reactivestreams" % zioReactiveStreamsInteropVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.6",

      "dev.zio" %% "zio-test" % zioVersion % "test",
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
}