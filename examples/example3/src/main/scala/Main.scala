import io.github.vigoo.zioaws.core.{AwsError, GenericAwsError}
import io.github.vigoo.zioaws.core.config.{AwsConfig, CommonAwsConfig}
import io.github.vigoo.zioaws.kinesis.Kinesis
import io.github.vigoo.zioaws.kinesis.model._
import io.github.vigoo.zioaws.netty.NettyHttpClient
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

  val program: ZIO[Has[Console] with Has[Kinesis], AwsError, Unit] =
    for {
      streams <- Kinesis.listStreams(ListStreamsRequest())
      _ <- Console.printLine("Streams:").ignore
      _ <- ZIO.foreachDiscard(streams.streamNames) { streamName =>
        Console.printLine(streamName).ignore
      }

      streamName = "test-stream"
      _ <- Console.printLine("Shards:").ignore
      shard <- Kinesis
        .listShards(ListShardsRequest(streamName = Some(streamName)))
        .tap(shard => Console.printLine(shard.shardId).ignore)
        .runHead
        .map(_.get)
      streamDescription <- Kinesis.describeStream(
        DescribeStreamRequest(streamName)
      )
      consumerName = "consumer1"

      _ <- Kinesis
        .registerStreamConsumer(
          RegisterStreamConsumerRequest(
            streamDescription.streamDescription.streamARN,
            consumerName
          )
        )
        .catchSome {
          case GenericAwsError(
                _: software.amazon.awssdk.services.kinesis.model.ResourceInUseException
              ) =>
            ZIO.unit
        }
      consumer <- Kinesis
        .describeStreamConsumer(
          DescribeStreamConsumerRequest(
            consumerName = Some(consumerName),
            streamARN =
              Some(streamDescription.streamDescription.streamARN)
          )
        )
        .repeatUntil(
          _.consumerDescription.consumerStatus == ConsumerStatus.ACTIVE
        )

      _ <- Console
        .printLine(
          s"Consumer registered: ${consumer.consumerDescription.consumerARN}"
        )
        .ignore

      shardStream = Kinesis.subscribeToShard(
        SubscribeToShardRequest(
          consumer.consumerDescription.consumerARN,
          shard.shardId,
          StartingPosition(ShardIteratorType.TRIM_HORIZON)
        )
      )

      _ <- Kinesis.putRecord(
        PutRecordRequest(
          streamName,
          data = Chunk.fromArray("sdf".getBytes),
          partitionKey = "123"
        )
      )

      _ <- shardStream
        .tap(event =>
          Console
            .printLine(event.records.map(_.partitionKey).toString())
            .ignore
        )
        .runHead

    } yield ()

  override def run: URIO[ZEnv with Has[ZIOAppArgs], ExitCode] = {
    val httpClient = NettyHttpClient.dual
    val cfg = ZLayer.succeed(
      CommonAwsConfig(
        region = Some(Region.US_EAST_1),
        credentialsProvider = StaticCredentialsProvider
          .create(AwsBasicCredentials.create(accessKey, secretKey)),
        endpointOverride = None,
        commonClientConfig = None
      )
    )
    val awsConfig = httpClient ++ cfg >>> AwsConfig.configured()
    val aws = awsConfig >>> Kinesis.live

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
