
scalaVersion := "2.13.3"

val zioAwsVersion = "2.14.2.1"

libraryDependencies ++= Seq(
  "io.github.vigoo" %% "zio-aws-core" % "2.14.2.2",
  "io.github.vigoo" %% "zio-aws-http4s" % "2.14.2.2",
  "io.github.vigoo" %% "zio-aws-netty" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-s3" % zioAwsVersion,
  "io.github.vigoo" %% "zio-aws-dynamodb" % zioAwsVersion,

  "dev.zio" %% "zio" % "1.0.1",
  "dev.zio" %% "zio-test" % "1.0.1",
  "dev.zio" %% "zio-test-sbt" % "1.0.1",

  "org.apache.logging.log4j" % "log4j-1.2-api" % "2.13.3",
  "org.apache.logging.log4j" % "log4j-core" % "2.13.3",
  "org.apache.logging.log4j" % "log4j-api" % "2.13.3",
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3",
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
