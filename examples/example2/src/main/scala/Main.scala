import zio.aws.core.aspects._
import zio.aws.core.AwsError
import zio.aws.core.config.{AwsConfig, CommonAwsConfig}
import zio.aws.dynamodb.model._
import zio.aws.dynamodb.{DynamoDb, model}
import zio.aws.netty.NettyHttpClient
// import nl.vroste.rezilience.{CircuitBreaker, Retry, TrippingStrategy}
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import zio._
import zio.config._

object Main extends ZIOAppDefault {
  val logging: AwsCallAspect[Clock] = ZIO.logLevel(LogLevel.Info) >>> callLogging    

  // def circuitBreaking(cb: CircuitBreaker[AwsError]): AwsCallAspect[Any] =
  //   new AwsCallAspect[Any] {
  //     override final def apply[R1 <: Any, A](
  //         f: ZIO[R1, AwsError, Described[A]]
  //     ): ZIO[R1, AwsError, Described[A]] =
  //       cb(f).mapError(policyError =>
  //         AwsError.fromThrowable(policyError.toException)
  //       )
  //   }

  val program: ZIO[Console & DynamoDb, AwsError, Unit] =
    for {
      _ <- Console.printLine("Performing full table scan").ignore
      scan = DynamoDb.scan(ScanRequest(tableName = TableName("test"))) // full table scan
      _ <- scan.foreach(item => Console.printLine(item.toString).ignore)
    } yield ()

  override def run: URIO[ZEnv with ZIOAppArgs, ExitCode] = {
    val httpClient = NettyHttpClient.default
    val config = ZLayer.succeed(
      CommonAwsConfig(
        region = Some(Region.US_EAST_1),
        credentialsProvider = DefaultCredentialsProvider.create(),
        endpointOverride = None,
        commonClientConfig = None
      )
    )
    val awsConfig = (httpClient ++ config) >>> AwsConfig.configured()

    // val circuitBreaker = CircuitBreaker.make[AwsError](
    //   trippingStrategy = TrippingStrategy.failureCount(maxFailures = 3),
    //   resetPolicy =
    //     Retry.Schedules.exponentialBackoff(min = 1.second, max = 1.minute)
    // )
    // circuitBreaker.use { cb =>
      // Default DynamoDB layer
      // val dynamoDb: ZLayer[AwsConfig, Throwable, DynamoDb] = dynamodb.live
      // DynamoDB with logging
      // val dynamoDb: ZLayer[Clock with Logging with AwsConfig, Throwable, DynamoDb] = dynamodb.live @@ logging
      // DynamoDB with circuit breaker
      // val dynamoDb: ZLayer[AwsConfig, Throwable, DynamoDb] = dynamodb.live @@ circuitBreaking(cb)

      val dynamoDb = (DynamoDb.live @@ logging) // (callLogging >>> circuitBreaking(cb)))
      val finalLayer = (Clock.any ++ awsConfig) >>> dynamoDb

      program
        .provideCustomLayer(finalLayer)
        .either
        .flatMap {
          case Left(error) =>
            Console.printLineError(s"AWS error: $error").ignore.as(ExitCode.failure)
          case Right(_) =>
            ZIO.unit.as(ExitCode.success)
        }
    // }
  }
}
