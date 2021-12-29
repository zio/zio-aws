package zio.aws.integtests

import java.net.URI
import akka.actor.ActorSystem
import zio.aws.core._
import zio.aws.core.aspects._
import zio.aws.core.config._
import zio.aws.dynamodb.model._
import zio.aws.dynamodb._
import zio.aws.netty._
import zio.aws.http4s._
import zio.aws.akkahttp._
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object DynamoDbTests extends DefaultRunnableSpec with Logging {

  val nettyClient = NettyHttpClient.default
  val http4sClient = Http4sClient.default
  val actorSystem =
    ZLayer.fromAcquireRelease(ZIO.attempt(ActorSystem("test")))(sys =>
      ZIO.fromFuture(_ => sys.terminate()).orDie
    )
  val akkaHttpClient = AkkaHttpClient.client()
  val awsConfig = AwsConfig.default
  val dynamoDb = DynamoDb.customized(
    _.credentialsProvider(
      StaticCredentialsProvider
        .create(AwsBasicCredentials.create("dummy", "key"))
    ).region(Region.US_WEST_2)
      .endpointOverride(new URI("http://localhost:4566"))
  ) @@ callLogging

  private def testTable(prefix: String) = {
    for {
      dynamodb <- ZIO.service[DynamoDb]
      console <- ZIO.service[Console]
      postfix <- Random.nextInt.map(Math.abs)
      tableName = s"${prefix}_$postfix"
    } yield ZManaged.acquireReleaseWith(
      for {
        _ <- Console.printLine(s"Creating table $tableName").ignore
        tableData <- DynamoDb.createTable(
          CreateTableRequest(
            tableName = tableName,
            attributeDefinitions = List(
              AttributeDefinition("key", ScalarAttributeType.S)
            ),
            keySchema = List(
              KeySchemaElement("key", KeyType.HASH)
            ),
            provisionedThroughput = Some(
              ProvisionedThroughput(
                readCapacityUnits = 16L,
                writeCapacityUnits = 16L
              )
            )
          )
        )
        tableDesc <- tableData.getTableDescription
      } yield tableDesc
    )(tableDescription =>
      tableDescription.getTableName
        .flatMap { tableName =>
          for {
            _ <- Console.printLine(s"Deleting table $tableName").ignore
            _ <- DynamoDb.deleteTable(DeleteTableRequest(tableName))
          } yield ()
        }
        .provideEnvironment(ZEnvironment(dynamodb) ++ ZEnvironment(console))
        .catchAll(error => ZIO.die(error.toThrowable))
        .unit
    )
  }

  def tests(prefix: String) =
    Seq(
      test("can create and delete a table") {
        // simple request/response calls
        val steps = for {
          table <- testTable(s"${prefix}_cd")
          _ <- table.use { _ =>
            ZIO.unit
          }
        } yield ()

        assertM(steps.exit)(succeeds(isUnit))
      } @@ nondeterministic @@ flaky @@ timeout(1.minute),
      test("scan") {
        // java paginator based streaming

        val N = 100
        val steps = for {
          table <- testTable(s"${prefix}_scn")
          result <- table.use { tableDescription =>
            val put =
              for {
                tableName <- tableDescription.getTableName
                randomKey <- Random.nextString(10)
                randomValue <- Random.nextInt
                _ <- DynamoDb.putItem(
                  PutItemRequest(
                    tableName = tableName,
                    item = Map(
                      "key" -> AttributeValue(s = Some(randomKey)),
                      "value" -> AttributeValue(n = Some(randomValue.toString))
                    )
                  )
                )
              } yield ()

            for {
              tableName <- tableDescription.getTableName
              _ <- put.repeatN(N - 1)
              stream = DynamoDb.scan(
                ScanRequest(
                  tableName = tableName,
                  limit = Some(10)
                )
              )
              streamResult <- stream.runCollect
            } yield streamResult
          }
        } yield result.length

        assertM(steps)(equalTo(N))
      } @@ nondeterministic @@ flaky @@ timeout(1.minute),
      test("listTagsOfResource") {
        // simple paginated streaming
        val N = 1000
        val steps = for {
          table <- testTable(s"${prefix}_lt")
          result <- table.use { tableDescription =>
            for {
              arn <- tableDescription.getTableArn
              _ <- DynamoDb.tagResource(
                TagResourceRequest(
                  resourceArn = arn,
                  tags = (0 until N)
                    .map(i => zio.aws.dynamodb.model.Tag(s"tag$i", i.toString))
                    .toList
                )
              )

              tagStream = DynamoDb.listTagsOfResource(
                ListTagsOfResourceRequest(
                  resourceArn = arn
                )
              )
              tags <- tagStream.runCollect
            } yield tags
          }
        } yield result.length

        assertM(steps)(equalTo(N))
      } @@ nondeterministic @@ flaky @@ timeout(1.minute)
    )

  override def spec = {
    suite("DynamoDB")(
      suite("with Netty")(
        tests("netty"): _*
      ).provideCustomLayer(
        ((Clock.any ++ Console.any ++ (nettyClient >>> awsConfig)) >>> dynamoDb)
          .mapError(TestFailure.die)
      ) @@ sequential,
      suite("with http4s")(
        tests("http4s"): _*
      ).provideCustomLayer(
        ((Clock.any ++ Console.any ++ (http4sClient >>> awsConfig)) >>> dynamoDb)
          .mapError(TestFailure.die)
      ) @@ ignore @@ sequential,
      suite("with akka-http")(
        tests("akkahttp"): _*
      ).provideCustomLayer(
        ((Clock.any ++ Console.any ++ (actorSystem >>> akkaHttpClient >>> awsConfig)) >>> dynamoDb)
          .mapError(TestFailure.die)
      ) @@ sequential
    ) @@ sequential
  }
}
