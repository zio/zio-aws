package zio.aws.codegen.githubactions

import io.circe._
import io.circe.syntax._
import zio.aws.codegen.githubactions.ScalaWorkflow.JavaVersion.AdoptJDK18

object ScalaWorkflow {
  import Step._

  def checkoutCurrentBranch(fetchDepth: Int = 0): Step =
    SingleStep(
      name = "Checkout current branch",
      uses = Some(ActionRef("actions/checkout@v2")),
      parameters = Map(
        "fetch-depth" := fetchDepth
      )
    )

  def setupScala(javaVersion: Option[JavaVersion] = None): Step =
    SingleStep(
      name = "Setup Java and Scala",
      uses = Some(ActionRef("olafurpg/setup-scala@v11")),
      parameters = Map(
        "java-version" := (javaVersion match {
          case None          => "${{ matrix.java }}"
          case Some(version) => version.asString
        })
      )
    )

  def setupNode(javaVersion: Option[JavaVersion] = None): Step =
    SingleStep(
      name = "Setup NodeJS",
      uses = Some(ActionRef("actions/setup-node@v3")),
      parameters = Map(
        "node-version" := (javaVersion match {
          case None          => "16.x"
          case Some(version) => version.asString
        }),
        "registry-url" := "https://registry.npmjs.org"
      )
    )

  def setupGPG(): Step =
    SingleStep(
      "Setup GPG",
      uses = Some(ActionRef("olafurpg/setup-gpg@v3"))
    )

  def cacheSBT(
      os: Option[OS] = None,
      scalaVersion: Option[ScalaVersion] = None
  ): Step = {
    val osS = os.map(_.asString).getOrElse("${{ matrix.os }}")
    val scalaS = scalaVersion.map(_.version).getOrElse("${{ matrix.scala }}")

    SingleStep(
      name = "Cache SBT",
      uses = Some(ActionRef("actions/cache@v2")),
      parameters = Map(
        "path" := Seq(
          "~/.ivy2/cache",
          "~/.sbt",
          "~/.coursier/cache/v1",
          "~/.cache/coursier/v1"
        ).mkString("\n"),
        "key" := s"$osS-sbt-$scalaS-$${{ hashFiles('**/*.sbt') }}-$${{ hashFiles('**/build.properties') }}"
      )
    )
  }

  def setupGitUser(): Step =
    SingleStep(
      name = "Setup GIT user",
      uses = Some(ActionRef("fregante/setup-git-user@v1"))
    )

  def runSBT(
      name: String,
      parameters: List[String],
      heapGb: Int = 6,
      stackMb: Int = 16,
      env: Map[String, String] = Map.empty
  ): Step =
    SingleStep(
      name,
      run = Some(
        s"sbt -J-XX:+UseG1GC -J-Xmx${heapGb}g -J-Xms${heapGb}g -J-Xss${stackMb}m ${parameters.mkString(" ")}"
      ),
      env = env
    )

  def storeTargets(
      id: String,
      directories: List[String],
      os: Option[OS] = None,
      scalaVersion: Option[ScalaVersion] = None,
      javaVersion: Option[JavaVersion] = None
  ): Step = {
    val osS = os.map(_.asString).getOrElse("${{ matrix.os }}")
    val scalaS = scalaVersion.map(_.version).getOrElse("${{ matrix.scala }}")
    val javaS = javaVersion.map(_.asString).getOrElse("${{ matrix.java }}")

    StepSequence(
      Seq(
        SingleStep(
          s"Compress $id targets",
          run = Some(
            s"tar cvf targets.tar ${directories.map(dir => s"$dir/target".dropWhile(_ == '/')).mkString(" ")}"
          )
        ),
        SingleStep(
          s"Upload $id targets",
          uses = Some(ActionRef("actions/upload-artifact@v2")),
          parameters = Map(
            "name" := s"target-$id-$osS-$scalaS-$javaS",
            "path" := "targets.tar"
          )
        )
      )
    )
  }

  def loadStoredTarget(
      id: String,
      os: Option[OS] = None,
      scalaVersion: Option[ScalaVersion] = None,
      javaVersion: Option[JavaVersion] = None
  ): Step = {
    val osS = os.map(_.asString).getOrElse("${{ matrix.os }}")
    val scalaS = scalaVersion.map(_.version).getOrElse("${{ matrix.scala }}")
    val javaS = javaVersion.map(_.asString).getOrElse("${{ matrix.java }}")

    StepSequence(
      Seq(
        SingleStep(
          s"Download stored $id targets",
          uses = Some(ActionRef("actions/download-artifact@v2")),
          parameters = Map(
            "name" := s"target-$id-$osS-$scalaS-$javaS"
          )
        ),
        SingleStep(
          s"Inflate $id targets",
          run = Some(
            "tar xvf targets.tar\nrm targets.tar"
          )
        )
      )
    )
  }

  def loadStoredTargets(
      ids: List[String],
      os: Option[OS] = None,
      scalaVersion: Option[ScalaVersion] = None,
      javaVersion: Option[JavaVersion] = None
  ): Step =
    StepSequence(
      ids.map(loadStoredTarget(_, os, scalaVersion, javaVersion))
    )

  def loadPGPSecret(): Step =
    SingleStep(
      "Load PGP secret",
      run = Some(".github/import-key.sh"),
      env = Map("PGP_SECRET" -> "${{ secrets.PGP_SECRET }}")
    )

  def turnstyle(): Step =
    SingleStep(
      "Turnstyle",
      uses = Some(ActionRef("softprops/turnstyle@v1")),
      env = Map(
        "GITHUB_TOKEN" -> "${{ secrets.ADMIN_GITHUB_TOKEN }}"
      )
    )

  def collectDockerLogs(): Step =
    SingleStep(
      "Collect Docker logs",
      uses = Some(ActionRef("jwalton/gh-docker-logs@v1"))
    )

  val isMaster: Condition = Condition.Expression(
    "github.ref == 'refs/heads/master'"
  )
  val isNotMaster: Condition = Condition.Expression(
    "github.ref != 'refs/heads/master'"
  )
  val isNotFromGithubActionBot: Condition = Condition.Expression(
    "github.actor != 'github-actions[bot]'"
  )
  def isScalaVersion(version: ScalaVersion): Condition = Condition.Expression(
    s"matrix.scala == '${version.version}'"
  )
  def isNotScalaVersion(version: ScalaVersion): Condition =
    Condition.Expression(
      s"matrix.scala != '${version.version}'"
    )
  val isFailure: Condition = Condition.Function("failure()")

  case class ScalaVersion(version: String)

  sealed trait JavaVersion {
    val asString: String
  }
  object JavaVersion {
    case object AdoptJDK18 extends JavaVersion {
      override val asString: String = "adopt@1.8"
    }
    case object ZuluJDK17 extends JavaVersion {
      override val asString: String = "zulu@1.17"
    }
  }

  implicit class JobOps(job: Job) {
    def matrix(
        scalaVersions: Seq[ScalaVersion],
        operatingSystems: Seq[OS] = Seq(OS.UbuntuLatest),
        javaVersions: Seq[JavaVersion] = Seq(AdoptJDK18)
    ): Job =
      job.copy(
        strategy = Some(
          Strategy(
            matrix = Map(
              "os" -> operatingSystems.map(_.asString).toList,
              "scala" -> scalaVersions.map(_.version).toList,
              "java" -> javaVersions.map(_.asString).toList
            )
          )
        ),
        runsOn = "${{ matrix.os }}"
      )
  }

}
