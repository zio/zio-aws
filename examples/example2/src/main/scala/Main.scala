import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.{core, dynamodb, netty}
import io.github.vigoo.zioaws.dynamodb.model.ScanRequest
import io.github.vigoo.zioaws.dynamodb.{DynamoDb, model}
import zio._
import zio.console.{Console, putStrLn}

object Main extends App {
  val program: ZIO[Console with DynamoDb, AwsError, Unit] =
    for {
      _ <- console.putStrLn("Performing full table scan")
      scan = dynamodb.scan(ScanRequest(tableName = "test")) // full table scan
      _ <- scan.foreach(item => putStrLn(item.toString)
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val httpClient = netty.client()
    val awsConfig = httpClient >>> core.config.default

    val ddb = dynamodb.live)
  }
}