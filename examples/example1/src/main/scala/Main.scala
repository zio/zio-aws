import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.ec2.Ec2
import io.github.vigoo.zioaws.ec2.model._
import io.github.vigoo.zioaws.elasticbeanstalk.ElasticBeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.model._
import io.github.vigoo.zioaws.{core, ec2, elasticbeanstalk, netty}
import zio._
import zio.stream._

object Main extends ZIOAppDefault {
  val program: ZIO[Has[Console] with Ec2 with ElasticBeanstalk, AwsError, Unit] =
    for {
      appsResult <- elasticbeanstalk.describeApplications(
        DescribeApplicationsRequest(applicationNames = Some(List("my-service")))
      )
      app <- appsResult.applications.map(_.headOption)
      _ <- app match {
        case Some(appDescription) =>
          for {
            applicationName <- appDescription.applicationName
            _ <- Console
              .printLine(
                s"Got application description for $applicationName"
              )
              .ignore

            envStream = elasticbeanstalk.describeEnvironments(
              DescribeEnvironmentsRequest(applicationName =
                Some(applicationName)
              )
            )

            _ <- envStream.run(Sink.foreach { env =>
              env.environmentName.flatMap { environmentName =>
                (for {
                  environmentId <- env.environmentId
                  _ <- Console
                    .printLine(
                      s"Getting the EB resources of $environmentName"
                    )
                    .ignore

                  resourcesResult <-
                    elasticbeanstalk.describeEnvironmentResources(
                      DescribeEnvironmentResourcesRequest(environmentId =
                        Some(environmentId)
                      )
                    )
                  resources <- resourcesResult.environmentResources
                  _ <- Console
                    .printLine(
                      s"Getting the EC2 instances in $environmentName"
                    )
                    .ignore
                  instances <- resources.instances
                  instanceIds <- ZIO.foreach(instances)(_.id)
                  _ <- Console
                    .printLine(
                      s"Instance IDs are ${instanceIds.mkString(", ")}"
                    )
                    .ignore

                  reservationsStream = ec2.describeInstances(
                    DescribeInstancesRequest(instanceIds = Some(instanceIds))
                  )
                  _ <- reservationsStream.run(Sink.foreach { reservation =>
                    reservation.instances
                      .flatMap { instances =>
                        ZIO.foreach(instances) { instance =>
                          for {
                            id <- instance.instanceId
                            typ <- instance.instanceType
                            launchTime <- instance.launchTime
                            _ <- Console.printLine(s"  instance $id:").ignore
                            _ <- Console.printLine(s"    type: $typ").ignore
                            _ <- Console
                              .printLine(
                                s"    launched at: $launchTime"
                              )
                              .ignore
                          } yield ()
                        }
                      }
                  })
                } yield ()).catchAll { error =>
                  Console
                    .printLineError(
                      s"Failed to get info for $environmentName: $error"
                    )
                    .ignore
                }
              }
            })
          } yield ()
        case None =>
          ZIO.unit
      }
    } yield ()

  override def run: URIO[ZEnv with Has[ZIOAppArgs], ExitCode] = {
    val httpClient = netty.default
    val awsConfig = httpClient >>> core.config.default
    val aws = awsConfig >>> (ec2.live ++ elasticbeanstalk.live)

    program
      .provideCustomLayer(aws)
      .either
      .flatMap {
        case Left(error) =>
          Console.printLineError(s"AWS error: $error").ignore.as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
