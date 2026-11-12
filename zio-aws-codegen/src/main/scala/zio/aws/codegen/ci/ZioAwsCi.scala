package zio.aws.codegen.ci

import zio.Chunk
import zio.json.ast.Json
import zio.sbt.githubactions._
import zio.sbt.githubactions.Step.SingleStep

/** The zio-aws CI jobs, handed to zio-sbt-ci through `ciBuildJobs` and
  * `ciReleaseJobs`.
  */
object ZioAwsCi {
  private val scala213 = "2.13.x"
  private val scala3 = "3.x"
  private val scalaVersions = List(scala213, scala3)

  private val isMaster =
    Condition.Expression("github.ref == 'refs/heads/master'")
  private val isNotMaster =
    Condition.Expression("github.ref != 'refs/heads/master'")
  private val notFromBot =
    Condition.Expression("github.actor != 'github-actions[bot]'")
  private def isScala(v: String) = Condition.Expression(s"matrix.scala == '$v'")
  private def isNotScala(v: String) =
    Condition.Expression(s"matrix.scala != '$v'")

  private val pgpEnv = Map(
    "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
    "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}"
  )

  private def action(ref: String) = Some(ActionRef(ref))

  private val checkout = SingleStep(
    "Checkout current branch",
    uses = action("actions/checkout@v4"),
    parameters = Map("fetch-depth" -> Json.Num(0))
  )

  private val setupJava = SingleStep(
    "Setup Java",
    uses = action("actions/setup-java@v4"),
    parameters = Map(
      "distribution" -> Json.Str("temurin"),
      "java-version" -> Json.Str("17"),
      "check-latest" -> Json.Str("true")
    )
  )

  private val setupSbt =
    SingleStep("Setup SBT", uses = action("sbt/setup-sbt@v1"))

  private val setupGpg: Step =
    SingleStep("Setup GPG", uses = action("olafurpg/setup-gpg@v3"))
      .when(isMaster)

  private val loadPgpSecret = SingleStep(
    "Load PGP secret",
    run = Some(".github/import-key.sh"),
    env = Map("PGP_SECRET" -> "${{ secrets.PGP_SECRET }}")
  )

  private def cacheSbt(
      os: String = "${{ matrix.os }}",
      scala: String = "${{ matrix.scala }}"
  ) =
    SingleStep(
      "Cache SBT",
      uses = action("actions/cache@v4"),
      parameters = Map(
        "path" -> Json.Str(
          Seq(
            "~/.ivy2/cache",
            "~/.sbt",
            "~/.coursier/cache/v1",
            "~/.cache/coursier/v1"
          ).mkString("\n")
        ),
        "key" -> Json.Str(
          s"$os-sbt-$scala-$${{ hashFiles('**/*.sbt') }}-$${{ hashFiles('**/build.properties') }}"
        )
      )
    )

  private def sbt(
      name: String,
      parameters: List[String],
      heapGb: Int = 6,
      env: Map[String, String] = Map.empty
  ) =
    SingleStep(
      name,
      run = Some(
        s"sbt -J-XX:+UseG1GC -J-Xmx${heapGb}g -J-Xms${heapGb}g -J-Xss16m ${parameters.mkString(" ")}"
      ),
      env = env
    )

  private def storeTargets(id: String, directories: List[String]): Step =
    Step.StepSequence(
      Seq(
        SingleStep(
          s"Compress $id targets",
          run = Some(
            s"tar cvf targets.tar ${directories.map(dir => s"$dir/target".dropWhile(_ == '/')).mkString(" ")}"
          )
        ),
        SingleStep(
          s"Upload $id targets",
          uses = action("actions/upload-artifact@v4"),
          parameters = Map(
            "name" -> Json.Str(
              s"target-$id-$${{ matrix.os }}-$${{ matrix.scala }}-$${{ matrix.java }}"
            ),
            "path" -> Json.Str("targets.tar")
          )
        )
      )
    )

  private def loadTargets(
      id: String,
      os: String,
      scala: String,
      java: String
  ): Step =
    Step.StepSequence(
      Seq(
        SingleStep(
          s"Download stored $id targets",
          uses = action("actions/download-artifact@v4"),
          parameters = Map("name" -> Json.Str(s"target-$id-$os-$scala-$java"))
        ),
        SingleStep(
          s"Inflate $id targets",
          run = Some("tar xvf targets.tar\nrm targets.tar")
        )
      )
    )

  private def loadMatrixTargets(id: String): Step =
    loadTargets(
      id,
      "${{ matrix.os }}",
      "${{ matrix.scala }}",
      "${{ matrix.java }}"
    )

  private val matrix = Strategy(
    matrix = Map(
      "os" -> List("ubuntu-latest"),
      "scala" -> scalaVersions,
      "java" -> List("17")
    )
  )

  private def groupsOf(
      moduleNames: Set[String],
      parallelJobs: Int,
      separateJobs: Set[String]
  ): List[List[String]] = {
    val sorted = moduleNames.map(n => s"zio-aws-$n").toList.sorted
    val (separate, rest) = sorted.partition(separateJobs.contains)
    rest
      .grouped(
        Math.ceil(moduleNames.size.toDouble / parallelJobs.toDouble).toInt
      )
      .toList ++ separate.map(List(_))
  }

  def buildJobs(
      moduleNames: Set[String],
      parallelJobs: Int,
      separateJobs: Set[String]
  ): Seq[Job] = {
    val groups = groupsOf(moduleNames, parallelJobs, separateJobs)
    val corePlugins = List(
      "zio-aws-core",
      "zio-aws-akka-http",
      "zio-aws-http4s",
      "zio-aws-netty",
      "zio-aws-crt-http"
    )

    val tag = Job(
      "tag",
      "Tag build",
      condition = Some(notFromBot),
      steps = Seq(
        checkout,
        setupJava,
        setupSbt,
        cacheSbt(os = "ubuntu-latest", scala = scala213),
        SingleStep(
          "Setup GIT user",
          uses = action("fregante/setup-git-user@v2")
        ),
        SingleStep(
          "Turnstyle",
          uses = action("softprops/turnstyle@v2"),
          env = Map("GITHUB_TOKEN" -> "${{ secrets.ADMIN_GITHUB_TOKEN }}")
        ).when(isMaster),
        sbt("Tag release", List("tagAwsVersion", "ciReleaseTagNextVersion"))
          .when(isMaster)
      )
    )

    val buildCore = Job(
      "build-core",
      "Build and test core",
      runsOn = "${{ matrix.os }}",
      strategy = Some(matrix),
      need = Seq("tag"),
      condition = Some(notFromBot),
      steps = Seq(
        checkout,
        setupJava,
        setupSbt,
        setupGpg,
        loadPgpSecret.when(isMaster),
        cacheSbt(),
        sbt(
          "Build and test core",
          "++${{ matrix.scala }}" :: corePlugins.map(_ + "/test")
        ),
        sbt(
          "Publish core",
          "++${{ matrix.scala }}" :: corePlugins.map(_ + "/publishSigned"),
          env = pgpEnv
        ).when(isMaster),
        storeTargets(
          "core",
          List("", "project", "zio-aws-codegen") ++ corePlugins
        )
      )
    )

    val integrationTest = Job(
      "integration-test",
      "Integration test",
      runsOn = "${{ matrix.os }}",
      strategy = Some(matrix),
      need = Seq("build-core"),
      services = Seq(
        Service(
          name = "floci",
          image = ImageRef("floci/floci:latest"),
          env = Map(
            "FLOCI_HOSTNAME" -> "localstack",
            "AWS_DEFAULT_REGION" -> "us-east-1",
            "AWS_ACCESS_KEY_ID" -> "dummy-key",
            "AWS_SECRET_ACCESS_KEY" -> "dummy-key",
            "QUARKUS_LOG_LEVEL" -> "DEBUG"
          ),
          ports = Chunk(ServicePort(4566, 4566))
        )
      ),
      condition = Some(notFromBot),
      steps = Seq(
        checkout,
        setupJava,
        setupSbt,
        cacheSbt(),
        loadMatrixTargets("core"),
        sbt(
          "Build and run tests",
          List("++${{ matrix.scala }}", "examples/compile", "integtests/test"),
          heapGb = 5
        ).when(isNotScala(scala3)),
        sbt(
          "Build and run tests",
          List("++${{ matrix.scala }}", "examples/compile"),
          heapGb = 5
        ).when(isScala(scala3)),
        SingleStep(
          "Collect Docker logs",
          uses = action("jwalton/gh-docker-logs@v1")
        )
          .when(Condition.Function("failure()"))
      )
    )

    val clients = groups.zipWithIndex.map { case (group, idx) =>
      Job(
        s"build-clients-$idx",
        s"Build client libraries #$idx",
        runsOn = "${{ matrix.os }}",
        strategy = Some(matrix),
        need = Seq("build-core", "integration-test"),
        condition = Some(notFromBot),
        steps = Seq(
          checkout,
          setupJava,
          setupSbt,
          setupGpg,
          loadPgpSecret.when(isMaster),
          cacheSbt(),
          loadMatrixTargets("core"),
          sbt(
            "Build libraries",
            "++${{ matrix.scala }}" :: group.map(_ + "/compile")
          )
            .when(isNotMaster),
          sbt(
            "Build and publish libraries",
            "++${{ matrix.scala }}" :: group.map(_ + "/publishSigned"),
            env = pgpEnv
          ).when(isMaster),
          storeTargets(s"clients-$idx", List("")).when(isMaster)
        )
      )
    }

    Seq(tag, buildCore, integrationTest) ++ clients
  }

  def releaseJobs(
      moduleNames: Set[String],
      parallelJobs: Int,
      separateJobs: Set[String]
  ): Seq[Job] = {
    val groups = groupsOf(moduleNames, parallelJobs, separateJobs)
    val ids = "core" :: groups.indices.map(idx => s"clients-$idx").toList

    Seq(
      Job(
        "release",
        "Release",
        need =
          Seq("build-core", "integration-test") ++ groups.indices.map(idx =>
            s"build-clients-$idx"
          ),
        condition = Some(isMaster && notFromBot),
        steps = Seq(
          checkout,
          setupJava,
          setupSbt,
          SingleStep("Setup GPG", uses = action("olafurpg/setup-gpg@v3")),
          loadPgpSecret,
          cacheSbt(os = "ubuntu-latest", scala = scala213),
          Step.StepSequence(
            ids.map(loadTargets(_, "ubuntu-latest", scala213, "17"))
          ),
          Step.StepSequence(
            ids.map(loadTargets(_, "ubuntu-latest", scala3, "17"))
          ),
          sbt(
            "Publish artifacts",
            List("sonaRelease"),
            heapGb = 5,
            env = pgpEnv ++ Map(
              "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}",
              "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}"
            )
          )
        )
      )
    )
  }

  /** Publishes the docs to npm as `@zio.dev/zio-aws` after every successful
    * release on master, then tells zio/zio to bump its pin.
    *
    * zio-sbt-ci's stock post-release jobs only run for `release: published` /
    * `workflow_dispatch`, which never happen here (versions are tags created by
    * the `tag` job), so they are built by hand. The package is published with
    * npm trusted publishing (OIDC), hence the job-level `id-token: write` and
    * no npm token.
    */
  def postReleaseJobs: Seq[Job] = {
    val npmPackage = "@zio.dev/zio-aws"

    val setupNode = SingleStep(
      "Setup NodeJs",
      uses = action("actions/setup-node@v7.1.0"),
      parameters = Map(
        "node-version" -> Json.Str("24.22.1"),
        "registry-url" -> Json.Str("https://registry.npmjs.org")
      )
    )

    val appTokenConfigured =
      Condition.Expression("steps.check-app-token.outputs.configured == 'true'")

    Seq(
      Job(
        "release-docs",
        "Release Docs",
        need = Seq("release"),
        condition = Some(isMaster && notFromBot),
        permissions = Map("id-token" -> "write", "contents" -> "read"),
        steps = Seq(
          checkout,
          setupJava,
          setupSbt,
          cacheSbt(os = "ubuntu-latest", scala = scala213),
          setupNode,
          sbt(
            "Publish Docs to NPM Registry",
            List(
              "++" + scala213,
              "generateArtifactList",
              "docs/publishHashverToNpm"
            ),
            heapGb = 4
          ),
          // Same job as the publish: the version is read from the package.json that
          // `publishHashverToNpm` just stamped. zio-sbt-ci's stock notify step asks
          // `npm view <pkg> version`, which is the `latest` dist-tag, and hash
          // versions are published under the `hash` tag, so it would announce the
          // previous version.
          SingleStep(
            "Check App Token",
            id = Some("check-app-token"),
            env = Map("APP_ID" -> "${{ secrets.APP_ID }}"),
            run = Some(
              """|if [ -n "$APP_ID" ]; then
                 |  echo "configured=true" >> "$GITHUB_OUTPUT"
                 |else
                 |  echo "configured=false" >> "$GITHUB_OUTPUT"
                 |fi""".stripMargin
            )
          ),
          // github_repo must be zio/zio: the dispatch below targets that repository.
          SingleStep(
            "Generate Token",
            id = Some("generate-token"),
            condition = Some(appTokenConfigured),
            uses = action("zio/generate-github-app-token@v1.0.0"),
            parameters = Map(
              "app_id" -> Json.Str("${{ secrets.APP_ID }}"),
              "app_private_key" -> Json.Str("${{ secrets.APP_PRIVATE_KEY }}"),
              "github_repo" -> Json.Str("zio/zio")
            )
          ),
          SingleStep(
            "Notify the main repo about the new version of the docs package",
            env = Map(
              "NOTIFY_TOKEN" -> "${{ steps.generate-token.outputs.token || secrets.PAT_TOKEN }}"
            ),
            run = Some(
              s"""|PACKAGE_NAME="$npmPackage"
                  |PACKAGE_VERSION=$$(node -p "require('./website/docs/package.json').version")
                  |curl -sSL --fail-with-body \\
                  |  -X POST \\
                  |  -H "Accept: application/vnd.github+json" \\
                  |  -H "Authorization: token $$NOTIFY_TOKEN" \\
                  |  https://api.github.com/repos/zio/zio/dispatches \\
                  |  -d '{"event_type":"update-docs","client_payload":{"package_name":"'"$$PACKAGE_NAME"'","package_version":"'"$$PACKAGE_VERSION"'"}}'""".stripMargin
            )
          )
        )
      )
    )
  }
}
