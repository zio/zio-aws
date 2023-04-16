import zio.aws.core.{AwsError, GenericAwsError}
import zio.aws.core.config.{AwsConfig, CommonAwsConfig}
import zio.aws.kinesis.Kinesis
import zio.aws.kinesis.model._
import zio.aws.kinesis.model.primitives._
import zio.aws.netty.NettyHttpClient
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

  val program: ZIO[Kinesis, AwsError, Unit] =
    for {
      _ <- Console.printLine("Streams:").ignore
      streamNames <- Kinesis.listStreams(ListStreamsRequest())
      _ <- ZIO.foreachDiscard(streamNames.streamNames) {
        streamName =>
          Console.printLine(streamName).ignore
      }

      streamName = StreamName("test-stream")
      _ <- Console.printLine("Shards:").ignore
      shard <- Kinesis
        .listShards(ListShardsRequest(streamName = streamName))
        .tap(shard => Console.printLine(shard.shardId).ignore)
        .runHead
        .map(_.get)
      streamDescription <- Kinesis.describeStream(
        DescribeStreamRequest(streamName)
      )
      consumerName = ConsumerName("consumer1")

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
            consumerName = consumerName,
            streamARN = streamDescription.streamDescription.streamARN
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
          data = Data(Chunk.fromArray("sdf".getBytes)),
          partitionKey = PartitionKey("123")
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

  override def run: ZIO[ZIOAppArgs with Scope, Nothing, ExitCode] = {
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
