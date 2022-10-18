import zio.aws.core.AwsError
import zio.aws.core.config.AwsConfig
import zio.aws.ec2.Ec2
import zio.aws.ec2.model._
import zio.aws.ec2.model.primitives._
import zio.aws.elasticbeanstalk.ElasticBeanstalk
import zio.aws.elasticbeanstalk.model._
import zio.aws.elasticbeanstalk.model.primitives._
import zio.aws.netty.NettyHttpClient
import zio._
import zio.stream._

object Main extends ZIOAppDefault {
  val program: ZIO[Ec2 & ElasticBeanstalk, AwsError, Unit] =
    for {
      appsResult <- ElasticBeanstalk.describeApplications(
        DescribeApplicationsRequest(applicationNames =
          List(ApplicationName("my-service"))
        )
      )
      app <- appsResult.getApplications.map(_.headOption)
      _ <- app match {
        case Some(appDescription) =>
          for {
            applicationName <- appDescription.getApplicationName
            _ <- Console
              .printLine(
                s"Got application description for $applicationName"
              )
              .ignore

            envStream = ElasticBeanstalk.describeEnvironments(
              DescribeEnvironmentsRequest(applicationName = applicationName)
            )

            _ <- envStream.run(ZSink.foreach { env =>
              env.getEnvironmentName.flatMap { environmentName =>
                (for {
                  environmentId <- env.getEnvironmentId
                  _ <- Console
                    .printLine(
                      s"Getting the EB resources of $environmentName"
                    )
                    .ignore

                  resourcesResult <-
                    ElasticBeanstalk.describeEnvironmentResources(
                      DescribeEnvironmentResourcesRequest(environmentId =
                        environmentId
                      )
                    )
                  resources <- resourcesResult.getEnvironmentResources
                  _ <- Console
                    .printLine(
                      s"Getting the EC2 instances in $environmentName"
                    )
                    .ignore
                  instances <- resources.getInstances
                  instanceIds <- ZIO.foreach(instances)(_.getId)
                  _ <- Console
                    .printLine(
                      s"Instance IDs are ${instanceIds.mkString(", ")}"
                    )
                    .ignore

                  reservationsStream = Ec2.describeInstances(
                    DescribeInstancesRequest(instanceIds =
                      instanceIds.map(id =>
                        zio.aws.ec2.model.primitives
                          .InstanceId(ResourceId.unwrap(id))
                      )
                    )
                  )
                  _ <- reservationsStream.run(ZSink.foreach { reservation =>
                    reservation.getInstances
                      .flatMap { instances =>
                        ZIO.foreach(instances) { instance =>
                          for {
                            id <- instance.getInstanceId
                            typ <- instance.getInstanceType
                            launchTime <- instance.getLaunchTime
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

  override def run: URIO[ZIOAppArgs with zio.Scope, ExitCode] = {
    val httpClient = NettyHttpClient.default
    val awsConfig = httpClient >>> AwsConfig.default
    val aws = awsConfig >>> (Ec2.live ++ ElasticBeanstalk.live)

    program
      .provideLayer(aws)
      .either
      .flatMap {
        case Left(error) =>
          Console
            .printLineError(s"AWS error: $error")
            .ignore
            .as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
