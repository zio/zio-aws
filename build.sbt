import com.jsuereth.sbtpgp.PgpKeys.{pgpPublicRing, pgpSecretRing}
import microsites.ConfigYml
import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

enablePlugins(Common, ZioAwsCodegenPlugin, GitVersioning)

ThisBuild / ciParallelJobs := 8
ThisBuild / ciSeparateJobs := Seq("")
ThisBuild / ciTarget := file(".github/workflows/ci.yml")
ThisBuild / artifactListTarget := file("docs/docs/docs/artifacts.md")

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
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.3",
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
      "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.0"
    )
  )
  .dependsOn(core)

lazy val akkahttp = Project("zio-aws-akka-http", file("zio-aws-akka-http"))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream" % "2.6.14",
      "com.typesafe.akka" %% "akka-http" % "10.2.4",
      "com.github.matsluni" %% "aws-spi-akka-http" % "0.0.11"
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
) aggregate (example1, example2, example3)

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

lazy val example3 = Project("example3", file("examples") / "example3")
  .dependsOn(
    core,
    http4s,
    netty,
    LocalProject("zio-aws-kinesis")
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

lazy val docs = project
  .enablePlugins(GhpagesPlugin, MicrositesPlugin)
  .settings(
    publishArtifact := false,
    skip in publish := true,
    scalaVersion := scala213Version,
    name := "zio-aws",
    description := "Low-level AWS wrapper for ZIO for all AWS services",
    git.remoteRepo := "git@github.com:vigoo/zio-aws.git",
    micrositeUrl := "https://vigoo.github.io",
    micrositeBaseUrl := "/zio-aws",
    micrositeHomepage := "https://vigoo.github.io/zio-aws/",
    micrositeDocumentationUrl := "/zio-aws/docs",
    micrositeAuthor := "Daniel Vigovszky",
    micrositeTwitterCreator := "@dvigovszky",
    micrositeGithubOwner := "vigoo",
    micrositeGithubRepo := "zio-aws",
    micrositeGitterChannel := false,
    micrositeDataDirectory := baseDirectory.value / "src/microsite/data",
    micrositeStaticDirectory := baseDirectory.value / "src/microsite/static",
    micrositeImgDirectory := baseDirectory.value / "src/microsite/img",
    micrositeCssDirectory := baseDirectory.value / "src/microsite/styles",
    micrositeSassDirectory := baseDirectory.value / "src/microsite/partials",
    micrositeJsDirectory := baseDirectory.value / "src/microsite/scripts",
    micrositeTheme := "light",
    micrositeHighlightLanguages ++= Seq("scala", "sbt"),
    micrositeConfigYaml := ConfigYml(
      yamlCustomProperties = Map(
        "url" -> "https://vigoo.github.io",
        "plugins" -> List("jemoji", "jekyll-sitemap")
      )
    ),
    micrositeAnalyticsToken := "UA-56320875-3",
    includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.txt" | "*.xml" | "*.svg",
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    // Temporary fix to avoid including mdoc in the published POM

    // skip dependency elements with a scope
    pomPostProcess := { (node: XmlNode) =>
      new RuleTransformer(new RewriteRule {
        override def transform(node: XmlNode): XmlNodeSeq = node match {
          case e: Elem if e.label == "dependency" && e.child.exists(child => child.label == "artifactId" && child.text.startsWith("mdoc_")) =>
            val organization = e.child.filter(_.label == "groupId").flatMap(_.text).mkString
            val artifact = e.child.filter(_.label == "artifactId").flatMap(_.text).mkString
            val version = e.child.filter(_.label == "version").flatMap(_.text).mkString
            Comment(s"dependency $organization#$artifact;$version has been omitted")
          case _ => node
        }
      }).transform(node).head
    }
  )
  .dependsOn(
    core,
    http4s,
    netty,
    akkahttp,
    LocalProject("zio-aws-elasticbeanstalk"),
    LocalProject("zio-aws-ec2")
  )