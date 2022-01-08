package zio.aws.codegen.generator

import io.circe.Json
import io.circe.syntax._
import io.circe.yaml
import io.circe.yaml.Printer.{LineBreak, StringStyle, YamlVersion}
import zio.aws.codegen.loader.ModelId
import zio.aws.codegen.githubactions._
import zio.aws.codegen.githubactions.ScalaWorkflow._
import zio.aws.codegen.githubactions.OS.UbuntuLatest
import zio.aws.codegen.githubactions.ScalaWorkflow.JavaVersion
import zio.aws.codegen.githubactions.ScalaWorkflow.JavaVersion.AdoptJDK18
import zio.aws.codegen.githubactions.{Branch, OS, Trigger}

trait GithubActionsGenerator {
  this: HasConfig with GeneratorBase =>

  def generateCiYaml(
      ids: Set[ModelId],
      parallelJobs: Int,
      separateJobs: Set[String]
  ): String = {
    val sortedProjectNames =
      ids
        .map(id => s"zio-aws-${id.moduleName}")
        .toList
        .sorted

    val (separateProjectNames, filteredProjectNames) =
      sortedProjectNames.partition(separateJobs.contains)

    val grouped = filteredProjectNames
      .grouped(
        Math.ceil(ids.size.toDouble / parallelJobs.toDouble).toInt
      )
      .toList ++ separateProjectNames.map(List(_))

    val scala212 = ScalaVersion("2.12.15")
    val scala213 = ScalaVersion("2.13.7")
    val scala3 = ScalaVersion("3.1.0")
    val scalaVersions = Seq(
      scala212,
      scala213,
      scala3
    )

    val workflow =
      Workflow("CI")
        .on(
          Trigger.PullRequest(
            ignoredBranches = Seq(Branch.Named("gh-pages"))
          ),
          Trigger.Push(
            ignoredBranches = Seq(Branch.Named("gh-pages"))
          )
        )
        .addJob(
          Job(
            "tag",
            "Tag build"
          ).withSteps(
            checkoutCurrentBranch(),
            setupScala(Some(AdoptJDK18)),
            cacheSBT(
              os = Some(UbuntuLatest),
              scalaVersion = Some(scala213)
            ),
            setupGitUser(),
            turnstyle().when(isMaster),
            runSBT(
              "Tag release",
              parameters = List("tagAwsVersion", "ciReleaseTagNextVersion")
            ).when(isMaster)
          )
        )
        .addJob(
          Job(
            "build-core",
            "Build and test core",
            need = Seq("tag")
          ).matrix(scalaVersions)
            .withSteps(
              checkoutCurrentBranch(),
              setupScala(),
              setupGPG().when(isMaster),
              loadPGPSecret.when(isMaster),
              cacheSBT(),
              runSBT(
                "Build and test core",
                parameters = List(
                  "++${{ matrix.scala }}",
                  "zio-aws-core/test",
                  "zio-aws-akka-http/test",
                  "zio-aws-http4s/test",
                  "zio-aws-netty/test"
                )
              ).when(isNotScalaVersion(scala3)),
              runSBT(
                "Build and test core",
                parameters = List(
                  "++${{ matrix.scala }}",
                  "zio-aws-core/test",
                  "zio-aws-netty/test"
                )
              ).when(isScalaVersion(scala3)),
              runSBT(
                "Publish core",
                parameters = List(
                  "++${{ matrix.scala }}",
                  "zio-aws-core/publishSigned",
                  "zio-aws-akka-http/publishSigned",
                  "zio-aws-http4s/publishSigned",
                  "zio-aws-netty/publishSigned"
                ),
                env = Map(
                  "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
                  "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}"
                )
              ).when(isMaster && isNotScalaVersion(scala3)),
              runSBT(
                "Publish core",
                parameters = List(
                  "++${{ matrix.scala }}",
                  "zio-aws-core/publishSigned",
                  "zio-aws-netty/publishSigned"
                ),
                env = Map(
                  "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
                  "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}"
                )
              ).when(isMaster && isScalaVersion(scala3)),
              storeTargets(
                "core",
                List(
                  "",
                  "project",
                  "zio-aws-codegen",
                  "zio-aws-core",
                  "zio-aws-akka-http",
                  "zio-aws-http4s",
                  "zio-aws-netty"
                )
              ).when(isNotScalaVersion(scala3)),
              storeTargets(
                "core",
                List(
                  "",
                  "project",
                  "zio-aws-codegen",
                  "zio-aws-core",
                  "zio-aws-netty"
                )
              ).when(isScalaVersion(scala3))
            )
        )
        .addJobs(
          grouped.zipWithIndex.map { case (group, idx) =>
            Job(
              s"build-clients-$idx",
              s"Build client libraries #$idx",
              need = Seq("build-core")
            ).matrix(scalaVersions)
              .withSteps(
                checkoutCurrentBranch(),
                setupScala(),
                setupGPG().when(isMaster),
                loadPGPSecret().when(isMaster),
                cacheSBT(),
                loadStoredTarget("core"),
                runSBT(
                  "Build libraries",
                  parameters = "++${{ matrix.scala }}" :: group
                    .map(name => s"$name/compile")
                ).when(isNotMaster),
                runSBT(
                  "Build and publish libraries",
                  parameters = "++${{ matrix.scala }}" :: group
                    .map(name => s"$name/publishSigned"),
                  env = Map(
                    "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
                    "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}"
                  )
                ).when(isMaster),
                storeTargets(
                  s"clients-$idx",
                  directories = List("")
                ).when(isMaster)
              )
          }
        )
        .addJob(
          Job(
            "integration-test",
            "Integration test",
            need = Seq("build-core")
          ).matrix(scalaVersions)
            .withServices(
              Service(
                name = "localstack",
                image = ImageRef("localstack/localstack:latest"),
                env = Map(
                  "LOCALSTACK_HOST" -> "localstack",
                  "SERVICES" -> "s3,dynamodb",
                  "USE_SSL" -> "false",
                  "DEFAULT_REGION" -> "us-east-1",
                  "AWS_DEFAULT_REGION" -> "us-east-1",
                  "AWS_ACCESS_KEY_ID" -> "dummy-key",
                  "AWS_SECRET_ACCESS_KEY" -> "dummy-key",
                  "DEBUG" -> "0"
                ),
                ports = Seq(
                  ServicePort(4566, 4566)
                )
              )
            )
            .withSteps(
              checkoutCurrentBranch(),
              setupScala(),
              cacheSBT(),
              loadStoredTarget("core"),
              runSBT(
                "Build and run tests",
                List(
                  "++${{ matrix.scala }}",
                  "examples/compile",
                  "integtests/test"
                ),
                heapGb = 5
              ).when(isNotScalaVersion(scala3)),
              runSBT(
                "Build and run tests",
                List(
                  "++${{ matrix.scala }}",
                  "examples/compile"
                ),
                heapGb = 5
              ).when(isScalaVersion(scala3))
            )
        )
        .addJob(
          Job(
            "release",
            "Release",
            need = Seq("build-core", "integration-test") ++
              grouped.indices.map(idx => s"build-clients-$idx"),
            condition = Some(isMaster)
          ).withSteps(
            checkoutCurrentBranch(),
            setupScala(Some(JavaVersion.AdoptJDK18)),
            setupGPG(),
            loadPGPSecret(),
            cacheSBT(
              os = Some(OS.UbuntuLatest),
              scalaVersion = Some(scala213)
            ),
            loadStoredTargets(
              "core" :: grouped.indices.map(idx => s"clients-$idx").toList,
              os = Some(OS.UbuntuLatest),
              scalaVersion = Some(scala213),
              javaVersion = Some(JavaVersion.AdoptJDK18)
            ),
            loadStoredTargets(
              "core" :: grouped.indices.map(idx => s"clients-$idx").toList,
              os = Some(OS.UbuntuLatest),
              scalaVersion = Some(scala212),
              javaVersion = Some(JavaVersion.AdoptJDK18)
            ),
            loadStoredTargets(
              "core" :: grouped.indices.map(idx => s"clients-$idx").toList,
              os = Some(OS.UbuntuLatest),
              scalaVersion = Some(scala3),
              javaVersion = Some(JavaVersion.AdoptJDK18)
            ),
            runSBT(
              "Publish artifacts",
              parameters = List(
                "sonatypeBundleRelease"
              ),
              heapGb = 5,
              env = Map(
                "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
                "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
                "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}",
                "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}"
              )
            )
          )
        )
        .addJob(
          Job(
            "microsite",
            "Build and publish microsite",
            need = Seq("release"),
            condition = Some(isMaster)
          ).withSteps(
            checkoutCurrentBranch(),
            setupScala(Some(JavaVersion.AdoptJDK18)),
            setupGPG(),
            cacheSBT(
              os = Some(OS.UbuntuLatest),
              scalaVersion = Some(scala213)
            ),            
            runSBT(
              "Build and publish microsite",
              parameters = List(
                "++2.13.3",
                "generateArtifactList",
                "docs/docusaurusPublishGhpages"
              ),
              heapGb = 4,
              env = Map(
                "GIT_DEPLOY_KEY" -> "${{ secrets.GIT_DEPLOY_KEY }}"
              )
            )
          )
        )

    yaml
      .Printer(
        preserveOrder = true,
        dropNullKeys = true,
        splitLines = true,
        lineBreak = LineBreak.Unix,
        version = YamlVersion.Auto
      )
      .pretty(workflow.asJson)
  }
}
