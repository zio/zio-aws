import com.jsuereth.sbtpgp.PgpKeys.{pgpPublicRing, pgpSecretRing}
enablePlugins(Common, ZioAwsCodegenPlugin, GitVersioning)

ThisBuild / circleCiParallelJobs := 8
ThisBuild / circleCiSource := file(".circleci/.config.base.yml")
ThisBuild / circleCiTarget := file(".circleci/config.yml")

Global / pgpPublicRing := file("/tmp/public.asc")
Global / pgpSecretRing := file("/tmp/secret.asc")
Global / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray())

lazy val root = Project("zio-aws", file(".")).settings(
  publishArtifact := false
) aggregate (core, http4s, netty, akkahttp)

lazy val core = Project("zio-aws-core", file("zio-aws-core"))
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "aws-core" % awsVersion,
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-interop-reactivestreams" % zioReactiveStreamsInteropVersion,
      "dev.zio" %% "zio-config" % zioConfigVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.2.0",
      "dev.zio" %% "zio-test" % zioVersion % "test",
      "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion % "test"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val http4s = Project("zio-aws-http4s", file("zio-aws-http4s"))
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-blaze-client" % http4sVersion,
      "software.amazon.awssdk" % "http-client-spi" % awsVersion,
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-interop-cats" % zioCatsInteropVersion,
      "dev.zio" %% "zio-config" % zioConfigVersion,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1"
    )
  )
  .dependsOn(core)

lazy val akkahttp = Project("zio-aws-akka-http", file("zio-aws-akka-http"))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream" % "2.6.10",
      "com.typesafe.akka" %% "akka-http" % "10.2.1",
      "com.github.matsluni" %% "aws-spi-akka-http" % "0.0.10"
    )
  )
  .dependsOn(core)

lazy val netty = Project("zio-aws-netty", file("zio-aws-netty"))
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "netty-nio-client" % awsVersion
    )
  )
  .dependsOn(core)

lazy val examples = Project("examples", file("examples")).settings(
  publishArtifact := false
) aggregate (example1, example2)

lazy val example1 = Project("example1", file("examples") / "example1")
  .dependsOn(
    core,
    http4s,
    netty,
    LocalProject("zio-aws-elasticbeanstalk"),
    LocalProject("zio-aws-ec2")
  )

lazy val example2 = Project("example2", file("examples") / "example2")
  .settings(
    resolvers += Resolver.jcenterRepo,
    libraryDependencies ++= Seq(
      "nl.vroste" %% "rezilience" % "0.5.0",
      "dev.zio" %% "zio-logging" % "0.5.0"
    )
  )
  .dependsOn(
    core,
    netty,
    LocalProject("zio-aws-dynamodb")
  )

lazy val integtests = Project("integtests", file("integtests"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-test" % zioVersion,
      "dev.zio" %% "zio-test-sbt" % zioVersion,
      "org.apache.logging.log4j" % "log4j-1.2-api" % "2.13.3",
      "org.apache.logging.log4j" % "log4j-core" % "2.13.3",
      "org.apache.logging.log4j" % "log4j-api" % "2.13.3",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(
    core,
    http4s,
    netty,
    akkahttp,
    LocalProject("zio-aws-s3"),
    LocalProject("zio-aws-dynamodb")
  )
