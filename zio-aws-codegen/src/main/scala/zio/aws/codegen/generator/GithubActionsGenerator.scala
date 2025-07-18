package zio.aws.codegen.generator

import io.circe.syntax.*
import io.circe.yaml
import io.circe.yaml.Printer.{LineBreak, YamlVersion}
import zio.aws.codegen.githubactions.OS.UbuntuLatest
import zio.aws.codegen.githubactions.ScalaWorkflow.JavaVersion.{
  Java17,
  Java11,
  Java21
}
import zio.aws.codegen.githubactions.ScalaWorkflow.*
import zio.aws.codegen.githubactions.*
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

    val scala213 = ScalaVersion("2.13.x")
    val scala3 = ScalaVersion("3.x")
    val scalaVersions = Seq(
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
            setupJava(Some(Java17)),
            setupSbt(),
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
              setupJava(Some(Java17)),
              setupSbt(),
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
                  "zio-aws-netty/test",
                  "zio-aws-crt-http/test"
                )
              ),
              runSBT(
                "Publish core",
                parameters = List(
                  "++${{ matrix.scala }}",
                  "zio-aws-core/publishSigned",
                  "zio-aws-akka-http/publishSigned",
                  "zio-aws-http4s/publishSigned",
                  "zio-aws-netty/publishSigned",
                  "zio-aws-crt-http/publishSigned"
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
                  "zio-aws-netty",
                  "zio-aws-crt-http"
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
              setupJava(Some(Java17)),
              setupSbt(),
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
                setupJava(Some(Java17)),
                setupSbt(),
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
            setupJava(Some(JavaVersion.Java17)),
            setupSbt(),
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
              javaVersion = Some(JavaVersion.Java17)
            ),
            loadStoredTargets(
              "core" :: grouped.indices.map(idx => s"clients-$idx").toList,
              os = Some(OS.UbuntuLatest),
              scalaVersion = Some(scala3),
              javaVersion = Some(JavaVersion.Java17)
            ),
            runSBT(
              "Publish artifacts",
              parameters = List("sonaRelease"),
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
