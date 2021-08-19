import io.github.vigoo.zioaws.core.{AwsError, GenericAwsError}
import io.github.vigoo.zioaws.core.config.CommonAwsConfig
import io.github.vigoo.zioaws.kinesis.Kinesis
import io.github.vigoo.zioaws.kinesis.model._
import io.github.vigoo.zioaws.{core, kinesis, netty}
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import zio.{console, _}
import zio.console._
import zio.stream._

object Main extends App {
  val accessKey = "TODO"
  val secretKey = "TODO"

  val program: ZIO[Console with Kinesis, AwsError, Unit] =
    for {
      streams <- kinesis.listStreams(ListStreamsRequest())
      _ <- console.putStrLn("Streams:").ignore
      _ <- ZIO.foreach_(streams.streamNamesValue) { streamName =>
        console.putStrLn(streamName).ignore
      }

      streamName = "test-stream"
      _ <- console.putStrLn("Shards:").ignore
      shard <- kinesis
        .listShards(ListShardsRequest(streamName = Some(streamName)))
        .tap(shard => console.putStrLn(shard.shardIdValue).ignore)
        .runHead
        .map(_.get)
      streamDescription <- kinesis.describeStream(
        DescribeStreamRequest(streamName)
      )
      consumerName = "consumer1"

      _ <- kinesis
        .registerStreamConsumer(
          RegisterStreamConsumerRequest(
            streamDescription.streamDescriptionValue.streamARNValue,
            consumerName
          )
        )
        .catchSome {
          case GenericAwsError(
                _: software.amazon.awssdk.services.kinesis.model.ResourceInUseException
              ) =>
            ZIO.unit
        }
      consumer <- kinesis
        .describeStreamConsumer(
          DescribeStreamConsumerRequest(
            consumerName = Some(consumerName),
            streamARN =
              Some(streamDescription.streamDescriptionValue.streamARNValue)
          )
        )
        .repeatUntil(
          _.consumerDescriptionValue.consumerStatusValue == ConsumerStatus.ACTIVE
        )

      _ <- console
        .putStrLn(
          s"Consumer registered: ${consumer.consumerDescriptionValue.consumerARNValue}"
        )
        .ignore

      shardStream = kinesis.subscribeToShard(
        SubscribeToShardRequest(
          consumer.consumerDescriptionValue.consumerARNValue,
          shard.shardIdValue,
          StartingPosition(ShardIteratorType.TRIM_HORIZON)
        )
      )

      _ <- kinesis.putRecord(
        PutRecordRequest(
          streamName,
          data = Chunk.fromArray("sdf".getBytes),
          partitionKey = "123"
        )
      )

      _ <- shardStream
        .tap(event =>
          console
            .putStrLn(event.recordsValue.map(_.partitionKeyValue).toString())
            .ignore
        )
        .runHead

    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val httpClient = netty.dual
    val cfg = ZLayer.succeed(
      CommonAwsConfig(
        region = Some(Region.US_EAST_1),
        credentialsProvider = StaticCredentialsProvider
          .create(AwsBasicCredentials.create(accessKey, secretKey)),
        endpointOverride = None,
        commonClientConfig = None
      )
    )
    val awsConfig = httpClient ++ cfg >>> core.config.configured()
    val aws = awsConfig >>> kinesis.live

    program
      .provideCustomLayer(aws)
      .either
      .flatMap {
        case Left(error) =>
          console.putStrErr(s"AWS error: $error").ignore.as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
