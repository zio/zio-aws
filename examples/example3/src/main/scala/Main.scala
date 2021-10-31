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
import zio._
import zio.stream._

object Main extends ZIOAppDefault {
  val accessKey = "TODO"
  val secretKey = "TODO"

  val program: ZIO[Has[Console] with Kinesis, AwsError, Unit] =
    for {
      streams <- kinesis.listStreams(ListStreamsRequest())
      _ <- Console.printLine("Streams:").ignore
      _ <- ZIO.foreachDiscard(streams.streamNamesValue) { streamName =>
        Console.printLine(streamName).ignore
      }

      streamName = "test-stream"
      _ <- Console.printLine("Shards:").ignore
      shard <- kinesis
        .listShards(ListShardsRequest(streamName = Some(streamName)))
        .tap(shard => Console.printLine(shard.shardIdValue).ignore)
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

      _ <- Console
        .printLine(
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
          Console
            .printLine(event.recordsValue.map(_.partitionKeyValue).toString())
            .ignore
        )
        .runHead

    } yield ()

  override def run: URIO[ZEnv with Has[ZIOAppArgs], ExitCode] = {
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
          Console.printLineError(s"AWS error: $error").ignore.as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
