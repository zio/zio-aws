import scala.jdk.CollectionConverters._
import zio._
import zio.console
import zio.console._
import zio.stream._
import io.github.vigoo.zioaws.core
import io.github.vigoo.zioaws.http4s
import io.github.vigoo.zioaws.netty
import io.github.vigoo.zioaws.ec2
import io.github.vigoo.zioaws.ec2.Ec2
import io.github.vigoo.zioaws.elasticbeanstalk
import io.github.vigoo.zioaws.elasticbeanstalk.ElasticBeanstalk
import io.github.vigoo.zioaws.core.AwsError
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.elasticbeanstalk.model.{DescribeApplicationsRequest, DescribeEnvironmentResourcesRequest, DescribeEnvironmentsRequest}

object Main extends App {

  val program: ZIO[Console with Ec2 with ElasticBeanstalk, AwsError, Unit] =
    for {
      app <- elasticbeanstalk.describeApplications(
        DescribeApplicationsRequest.builder()
          .applicationNames("my-service")
          .build()
      ).map(_.applications().asScala.headOption)
      _ <- app match {
        case Some(appDescription) =>
          for {
            _ <- console.putStrLn(s"Got application description for ${appDescription.applicationName()}")
            envs <- elasticbeanstalk.describeEnvironments(
              DescribeEnvironmentsRequest.builder()
                .applicationName(appDescription.applicationName())
                .build()
            ).map(_.environments().asScala.toList)
            _ <- ZIO.foreach(envs) { env =>
              (for {
                _ <- console.putStrLn(s"Getting the EB resources of ${env.environmentName()}")
                instances <- elasticbeanstalk.describeEnvironmentResources(
                  DescribeEnvironmentResourcesRequest.builder()
                    .environmentId(env.environmentId())
                    .build()).map(_.environmentResources().instances().asScala)
                _ <- console.putStrLn(s"Getting the EC2 instances in ${env.environmentName()}")
                instanceIds = instances.map(_.id())
                _ <- console.putStrLn(s"Instance IDs are ${instanceIds.mkString(", ")}")
                reservationsStream <- ec2.describeInstancesStream(
                  DescribeInstancesRequest.builder()
                    .instanceIds(instanceIds.asJavaCollection)
                    .build())
                _ <- reservationsStream.run(Sink.foreach {
                  reservation =>
                    ZIO.foreach(reservation.instances().asScala.toList) { instance =>
                      for {
                        _ <- console.putStrLn(s"  instance ${instance.instanceId()}:")
                        _ <- console.putStrLn(s"    type: ${instance.instanceType()}")
                        _ <- console.putStrLn(s"    launched at: ${instance.launchTime()}")
                      } yield ()
                    }
                })
              } yield ()).catchAll { error =>
                console.putStrLnErr(s"Failed to get info for ${env.environmentName()}: $error")
              }
            }
          } yield ()
        case None =>
          ZIO.unit
      }
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val httpClient = http4s.client()
    //val httpClient = netty.client()
    val awsConfig = httpClient >>> core.config.default
    val aws = awsConfig >>> (ec2.live ++ elasticbeanstalk.live)

    program.provideCustomLayer(aws)
      .either
      .flatMap {
        case Left(error) =>
          console.putStrErr(s"AWS error: $error").as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
