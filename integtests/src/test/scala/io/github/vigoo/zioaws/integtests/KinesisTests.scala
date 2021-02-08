package io.github.vigoo.zioaws.integtests

import akka.actor.ActorSystem
import io.github.vigoo.zioaws._
import io.github.vigoo.zioaws.core.{GenericAwsError, config}
import io.github.vigoo.zioaws.core.config.CommonAwsConfig
import io.github.vigoo.zioaws.kinesis.Kinesis
import io.github.vigoo.zioaws.kinesis.model._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.model.ResourceInUseException
import zio._
import zio.clock.Clock
import zio.duration.durationInt
import zio.test.TestAspect._
import zio.test._

import java.net.URI

object KinesisTests extends DefaultRunnableSpec {

  val nettyClient = netty.dual
  val http4sClient = http4s.default
  val actorSystem =
    ZLayer.fromAcquireRelease(ZIO.effect(ActorSystem("test")))(sys =>
      ZIO.fromFuture(_ => sys.terminate()).orDie
    )
  val akkaHttpClient = actorSystem >>> akkahttp.client()
  val awsConfig = (ZLayer.succeed(
    CommonAwsConfig(
      region = Some(Region.US_EAST_1),
      credentialsProvider = StaticCredentialsProvider
        .create(AwsBasicCredentials.create("test", "test")),
      endpointOverride = Some(new URI("http://localhost:4566")),
      commonClientConfig = None
    )
  ) ++ nettyClient) >>> config.configured()

  override def spec = {
    suite("Kinesis")(
      testM("subscribeToShard") {
        val streamName = "test1"
        val consumerName = "consumer1"
        for {
          _ <- kinesis
            .createStream(CreateStreamRequest(streamName, 1))
            .catchSome {
              case GenericAwsError(
                    _: software.amazon.awssdk.services.kinesis.model.ResourceInUseException
                  ) =>
                ZIO.unit
            }
          // Wait until stream is created
          shards <- getShards(streamName)
          shard = shards.head
          streamDescription <- kinesis.describeStream(
            DescribeStreamRequest(streamName)
          )
          _ <- kinesis.putRecord(
            PutRecordRequest(streamName, Chunk.fromArray("sdf".getBytes), "123")
          )
          _ <- Task(println(s"arn: ${streamDescription.streamDescriptionValue.streamARNValue}"))
          consumer <- kinesis
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
          shardStream = kinesis.subscribeToShard(
            SubscribeToShardRequest(
              consumer.consumerDescriptionValue.consumerARNValue,
              shard.shardIdValue,
              StartingPosition(ShardIteratorType.TRIM_HORIZON)
            )
          )
          firstEvent <- shardStream.runHead
          _ = println(firstEvent)
        } yield assertCompletes
      }
    )
      .provideCustomLayer(
        zio.clock.Clock.live >+> (awsConfig >>> kinesis.live)
          .mapError(TestFailure.die)
      ) @@ sequential @@ beforeAll(Task(System.setProperty(SdkSystemSetting.CBOR_ENABLED.property, "false")).orDie)
  }

  private def getShards(
      name: String
  ): ZIO[Kinesis with Clock, Throwable, Chunk[Shard.ReadOnly]] =
    kinesis
      .listShards(ListShardsRequest(streamName = Some(name)))
      .mapError(_.toThrowable)
      .runCollect
      .filterOrElse(_.nonEmpty)(_ => getShards(name).delay(1.second))
      .catchSome { case _: ResourceInUseException =>
        getShards(name).delay(1.second)
      }
}
