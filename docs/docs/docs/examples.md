---
layout: docs
title: Examples
---

# Examples

The following example uses the ElasticBeanstalk and EC2 APIs to print some info. 

```scala mdoc:invisible
import zio.aws.core.AwsError
import zio.aws.ec2.Ec2
import zio.aws.ec2.model._
import zio.aws.elasticbeanstalk.ElasticBeanstalk
import zio.aws.elasticbeanstalk.model._
import zio.aws.{core, ec2, elasticbeanstalk, http4s, netty}
import zio.{console, _}
import zio.console._
import zio.stream._
```

```scala mdoc
object Main extends zio.App {
  val program: ZIO[Console with Ec2 with ElasticBeanstalk, AwsError, Unit] =
    for {
      appsResult <- elasticbeanstalk.describeApplications(DescribeApplicationsRequest(applicationNames = Some(List("my-service"))))
      app <- appsResult.applications.map(_.headOption)
      _ <- app match {
        case Some(appDescription) =>
          for {
            applicationName <- appDescription.applicationName
            _ <- console.putStrLn(s"Got application description for $applicationName").ignore

            envStream = elasticbeanstalk.describeEnvironments(DescribeEnvironmentsRequest(applicationName = Some(applicationName)))

            _ <- envStream.run(Sink.foreach { env =>
              env.environmentName.flatMap { environmentName =>
                (for {
                  environmentId <- env.environmentId
                  _ <- console.putStrLn(s"Getting the EB resources of $environmentName").ignore

                  resourcesResult <- elasticbeanstalk.describeEnvironmentResources(DescribeEnvironmentResourcesRequest(environmentId = Some(environmentId)))
                  resources <- resourcesResult.environmentResources
                  _ <- console.putStrLn(s"Getting the EC2 instances in $environmentName").ignore
                  instances <- resources.instances
                  instanceIds <- ZIO.foreach(instances)(_.id)
                  _ <- console.putStrLn(s"Instance IDs are ${instanceIds.mkString(", ")}").ignore

                  reservationsStream = ec2.describeInstances(DescribeInstancesRequest(instanceIds = Some(instanceIds)))
                  _ <- reservationsStream.run(Sink.foreach {
                    reservation =>
                      reservation.instances.flatMap { instances =>
                        ZIO.foreach(instances) { instance =>
                          for {
                            id <- instance.instanceId
                            typ <- instance.instanceType
                            launchTime <- instance.launchTime
                            _ <- console.putStrLn(s"  instance $id:").ignore
                            _ <- console.putStrLn(s"    type: $typ").ignore
                            _ <- console.putStrLn(s"    launched at: $launchTime").ignore
                          } yield ()
                        }
                      }
                  })
                } yield ()).catchAll { error =>
                  console.putStrLnErr(s"Failed to get info for $environmentName: $error").ignore
                }
              }
            })
          } yield ()
        case None =>
          ZIO.unit
      }
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val httpClient = http4s.default
    //val httpClient = netty.default
    val awsConfig = httpClient >>> core.config.default
    val aws = awsConfig >>> (ec2.live ++ elasticbeanstalk.live)

    program.provideCustomLayer(aws)
      .either
      .flatMap {
        case Left(error) =>
          console.putStrErr(s"AWS error: $error").ignore.as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
``` 
