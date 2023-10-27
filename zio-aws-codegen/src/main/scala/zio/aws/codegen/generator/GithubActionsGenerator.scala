package zio.aws.codegen.generator

import io.circe.syntax._
import io.circe.yaml
import io.circe.yaml.Printer.{LineBreak, YamlVersion}
import zio.aws.codegen.githubactions.OS.UbuntuLatest
import zio.aws.codegen.githubactions.ScalaWorkflow.JavaVersion.{
  AdoptJDK18,
  ZuluJDK17
}
import zio.aws.codegen.githubactions.ScalaWorkflow.{JavaVersion, _}
import zio.aws.codegen.githubactions._
import zio.aws.codegen.loader.ModuleId

trait GithubActionsGenerator {
  this: HasConfig with GeneratorBase =>

  def generateCiYaml(
      ids: Set[ModuleId],
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

    val scala212 = ScalaVersion("2.12.18")
    val scala213 = ScalaVersion("2.13.12")
    val scala3 = ScalaVersion("3.2.2")
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
            branches = Seq(Branch.Named("master"))
          )
        )
        .addJob(
          Job(
            "tag",
            "Tag build",
            condition = Some(isNotFromGithubActionBot)
          ).withSteps(
            checkoutCurrentBranch(),
            setupScala(Some(ZuluJDK17)),
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
            need = Seq("tag"),
            condition = Some(isNotFromGithubActionBot)
          ).matrix(scalaVersions)
            .withSteps(
              checkoutCurrentBranch(),
              setupScala(Some(ZuluJDK17)),
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
              ),
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
              ).when(isMaster),
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
              )
            )
        )
        .addJob(
          Job(
            "integration-test",
            "Integration test",
            need = Seq("build-core"),
            condition = Some(isNotFromGithubActionBot)
          ).matrix(scalaVersions)
            .withServices(
              Service(
                name = "localstack",
                image = ImageRef("localstack/localstack:latest"),
                env = Map(
                  "LOCALSTACK_HOST" -> "localstack",
                  "SERVICES" -> "s3,dynamodb",
                  "EAGER_SERVICE_LOADING" -> "1",
                  "USE_SSL" -> "false",
                  "DEFAULT_REGION" -> "us-east-1",
                  "AWS_DEFAULT_REGION" -> "us-east-1",
                  "AWS_ACCESS_KEY_ID" -> "dummy-key",
                  "AWS_SECRET_ACCESS_KEY" -> "dummy-key",
                  "DEBUG" -> "1"
                ),
                ports = Seq(
                  ServicePort(4566, 4566)
                )
              )
            )
            .withSteps(
              checkoutCurrentBranch(),
              setupScala(Some(ZuluJDK17)),
              cacheSBT(),
              loadStoredTarget("core"),
              runSBT(
                "Build and run tests",
                List(
                  "++${{ matrix.scala }}",
                  "integtests/test"
                ),
                heapGb = 5
              ).when(isScalaVersion(scala212)),
              runSBT(
                "Build and run tests",
                List(
                  "++${{ matrix.scala }}",
                  "examples/compile",
                  "integtests/test"
                ),
                heapGb = 5
              ).when(isNotScalaVersion(scala3) && isNotScalaVersion(scala212)),
              runSBT(
                "Build and run tests",
                List(
                  "++${{ matrix.scala }}",
                  "examples/compile"
                ),
                heapGb = 5
              ).when(isScalaVersion(scala3)),
              collectDockerLogs().when(isFailure)
            )
        )
        .addJobs(
          grouped.zipWithIndex.map { case (group, idx) =>
            Job(
              s"build-clients-$idx",
              s"Build client libraries #$idx",
              need = Seq("build-core", "integration-test"),
              condition = Some(isNotFromGithubActionBot)
            ).matrix(scalaVersions)
              .withSteps(
                checkoutCurrentBranch(),
                setupScala(Some(ZuluJDK17)),
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
            "release",
            "Release",
            need = Seq("build-core", "integration-test") ++
              grouped.indices.map(idx => s"build-clients-$idx"),
            condition = Some(isMaster && isNotFromGithubActionBot)
          ).withSteps(
            checkoutCurrentBranch(),
            setupScala(Some(JavaVersion.ZuluJDK17)),
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
