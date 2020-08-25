package io.github.vigoo.zioaws.integtests

import java.net.URI

import akka.actor.ActorSystem
import io.github.vigoo.zioaws.core._
import io.github.vigoo.zioaws.{dynamodb, _}
import io.github.vigoo.zioaws.dynamodb.model._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import zio._
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestRandom

object DynamoDbTests extends DefaultRunnableSpec {

  val nettyClient = netty.client()
  val http4sClient = http4s.client()
  val actorSystem = ActorSystem("test") // ZLayer.fromAcquireRelease(ZIO.effect(ActorSystem("test")))(sys => ZIO.fromFuture(_ => sys.terminate()).orDie)
  val akkaHttpClient = akkahttp.client(actorSystem)
  val awsConfig = config.default
  val dynamoDb = dynamodb.customized(
    _.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "key")))
      .endpointOverride(new URI("http://localhost:4566"))
  )

  private def testTable = {
    for {
      _ <- TestRandom.setSeed(scala.util.Random.nextLong())
      tableName <- generateName
      env <- ZIO.environment[dynamodb.DynamoDb]
    } yield ZManaged.make(
      for {
        tableData <- dynamodb.createTable(CreateTableRequest(
          tableName = tableName,
          attributeDefinitions = List(
            AttributeDefinition("key", ScalarAttributeType.S)
          ),
          keySchema = List(
            KeySchemaElement("key", KeyType.HASH)
          ),
          provisionedThroughput = Some(ProvisionedThroughput(
            readCapacityUnits = 16L,
            writeCapacityUnits = 16L
          ))
        ))
        tableDesc <- tableData.tableDescription
      } yield tableDesc
    )(tableDescription =>
      tableDescription.tableName.flatMap { tableName =>
        dynamodb.deleteTable(DeleteTableRequest(tableName))
      }.provide(env)
       .catchAll(error => ZIO.die(error.toThrowable))
       .unit)
  }

  def tests = Seq(
    testM("can create and delete a table") {
      // simple request/response calls
      val steps = for {
        table <- testTable
        _ <- table.use { _ =>
          ZIO.unit
        }
      } yield ()

      assertM(steps.run)(succeeds(isUnit))
    },
    testM("scan") {
      // java paginator based streaming

      val N = 100
      val steps = for {
        table <- testTable
        result <- table.use { tableDescription =>
          val put =
            for {
              tableName <- tableDescription.tableName
              randomKey <- random.nextString(10)
              randomValue <- random.nextInt
              _ <- dynamodb.putItem(PutItemRequest(
                tableName = tableName,
                item = Map(
                  "key" -> AttributeValue(s = Some(randomKey)),
                  "value" -> AttributeValue(n = Some(randomValue.toString))
                )
              ))
            } yield ()

          for {
            tableName <- tableDescription.tableName
            _ <- put.repeatN(N - 1)
            stream = dynamodb.scan(ScanRequest(
              tableName = tableName,
              limit = Some(10)
            ))
            streamResult <- stream.runCollect
          } yield streamResult
        }
      } yield result.length

      assertM(steps)(equalTo(N))
    },
    testM("listTagsOfResource") {
      // simple paginated streaming
      val N = 1000
      val steps = for {
        table <- testTable
        result <- table.use { tableDescription =>
          for {
            arn <- tableDescription.tableArn
            _ <- dynamodb.tagResource(TagResourceRequest(
              resourceArn = arn,
              tags = (0 until N).map(i => dynamodb.model.Tag(s"tag$i", i.toString)).toList
            ))

            tagStream = dynamodb.listTagsOfResource(ListTagsOfResourceRequest(
              resourceArn = arn
            ))
            tags <- tagStream.runCollect
          } yield tags
        }
      } yield result.length

      assertM(steps)(equalTo(N))
    }
  )

  private def generateName =
    ZIO.foreach((0 to 8).toList) { _ =>
      random.nextIntBetween('a'.toInt, 'z'.toInt).map(_.toChar)
    }.map(_.mkString)


  override def spec = {
    suite("DynamoDB")(
      suite("with Netty")(
        tests: _*
      ).provideCustomLayer((nettyClient >>> awsConfig >>> dynamoDb).mapError(TestFailure.die)),
      suite("with http4s")(
        tests: _*
      ).provideCustomLayer((http4sClient >>> awsConfig >>> dynamoDb).mapError(TestFailure.die)),
      suite("with akka-http")(
        tests: _*
      ).provideCustomLayer((akkaHttpClient >>> awsConfig >>> dynamoDb).mapError(TestFailure.die)),
    )
  }
}