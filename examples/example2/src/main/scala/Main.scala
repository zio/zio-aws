import io.github.vigoo.zioaws.core.aspects._
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.core.config.{AwsConfig, CommonAwsConfig}
import io.github.vigoo.zioaws.{core, dynamodb, netty}
import io.github.vigoo.zioaws.dynamodb.model.ScanRequest
import io.github.vigoo.zioaws.dynamodb.{DynamoDb, model}
import nl.vroste.rezilience.{CircuitBreaker, Retry, TrippingStrategy}
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import zio._
import zio.clock.Clock
import zio.config._
import zio.console.{Console, putStrLn}
import zio.duration.durationInt
import zio.logging._

object Main extends App {
  val callLogging: AwsCallAspect[Clock with Logging] =
    new AwsCallAspect[Clock with Logging] {
      override final def apply[R1 <: Clock with Logging, A](
          f: ZIO[R1, AwsError, Described[A]]
      ): ZIO[R1, AwsError, Described[A]] = {
        f.timed.flatMap {
          case (duration, r @ Described(result, description)) =>
            log
              .info(
                s"[${description.service}/${description.operation}] ran for $duration"
              )
              .as(r)
        }
      }
    }

  def circuitBreaking(cb: CircuitBreaker[AwsError]): AwsCallAspect[Any] =
    new AwsCallAspect[Any] {
      override final def apply[R1 <: Any, A](
          f: ZIO[R1, AwsError, Described[A]]
      ): ZIO[R1, AwsError, Described[A]] =
        cb(f).mapError(policyError =>
          AwsError.fromThrowable(policyError.toException)
        )
    }

  val program: ZIO[Console with DynamoDb, AwsError, Unit] =
    for {
      _ <- console.putStrLn("Performing full table scan")
      scan = dynamodb.scan(ScanRequest(tableName = "test")) // full table scan
      _ <- scan.foreach(item => putStrLn(item.toString))
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val httpClient = netty.default
    val config = ZLayer.succeed(
      CommonAwsConfig(
        region = Some(Region.US_EAST_1),
        credentialsProvider = DefaultCredentialsProvider.create(),
        endpointOverride = None,
        commonClientConfig = None
      )
    )
    val awsConfig = (httpClient ++ config) >>> core.config.configured()
    val logging = Logging.consoleErr()

    val circuitBreaker = CircuitBreaker.make[AwsError](
      trippingStrategy = TrippingStrategy.failureCount(maxFailures = 3),
      resetPolicy =
        Retry.Schedules.exponentialBackoff(min = 1.second, max = 1.minute)
    )
    circuitBreaker.use { cb =>
      // Default DynamoDB layer
      // val dynamoDb: ZLayer[AwsConfig, Throwable, DynamoDb] = dynamodb.live
      // DynamoDB with logging
      // val dynamoDb: ZLayer[Clock with Logging with AwsConfig, Throwable, DynamoDb] = dynamodb.live @@ logging
      // DynamoDB with circuit breaker
      // val dynamoDb: ZLayer[AwsConfig, Throwable, DynamoDb] = dynamodb.live @@ circuitBreaking(cb)

      val dynamoDb = (dynamodb.live @@ (callLogging >>> circuitBreaking(cb)))
      val finalLayer = (Clock.any ++ awsConfig ++ logging) >>> dynamoDb

      program
        .provideCustomLayer(finalLayer)
        .either
        .flatMap {
          case Left(error) =>
            console.putStrErr(s"AWS error: $error").as(ExitCode.failure)
          case Right(_) =>
            ZIO.unit.as(ExitCode.success)
        }
    }
  }
}
