package io.github.vigoo.zioaws.integtests

import java.net.URI

import akka.actor.ActorSystem
import io.github.vigoo.zioaws.core._
import io.github.vigoo.zioaws.core.config
import io.github.vigoo.zioaws.dynamodb.model._
import io.github.vigoo.zioaws.{dynamodb, _}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object DynamoDbTests extends DefaultRunnableSpec {

  val nettyClient = netty.client()
  val http4sClient = http4s.client()
  val actorSystem = ZLayer.fromAcquireRelease(ZIO.effect(ActorSystem("test")))(sys => ZIO.fromFuture(_ => sys.terminate()).orDie)
  val akkaHttpClient = akkahttp.client()
  val awsConfig = config.default
  val dynamoDb = dynamodb.customized(
    _.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "key")))
      .region(Region.US_WEST_2)
      .endpointOverride(new URI("http://localhost:4566"))
  )

  private def testTable(prefix: String) = {
    for {
      env <- ZIO.environment[dynamodb.DynamoDb with zio.console.Console]
      postfix <- random.nextInt.map(Math.abs)
      tableName = s"${prefix}_$postfix"
    } yield ZManaged.make(
      for {
        _ <- console.putStrLn(s"Creating table $tableName")
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
        for {
          _ <- console.putStrLn(s"Deleting table $tableName")
          _ <- dynamodb.deleteTable(DeleteTableRequest(tableName))
        } yield ()
      }.provide(env)
       .catchAll(error => ZIO.die(error.toThrowable))
       .unit)
  }

  def tests(prefix: String) = Seq(
    testM("can create and delete a table") {
      // simple request/response calls
      val steps = for {
        table <- testTable(s"${prefix}_cd")
        _ <- table.use { _ =>
          ZIO.unit
        }
      } yield ()

      assertM(steps.run)(succeeds(isUnit))
    } @@ nondeterministic @@ flaky,
    testM("scan") {
      // java paginator based streaming

      val N = 100
      val steps = for {
        table <- testTable(s"${prefix}_scn")
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
    } @@ nondeterministic @@ flaky,
    testM("listTagsOfResource") {
      // simple paginated streaming
      val N = 1000
      val steps = for {
        table <- testTable(s"${prefix}_lt")
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
    } @@ nondeterministic @@ flaky
  )

  override def spec = {
    suite("DynamoDB")(
      suite("with Netty")(
        tests("netty"): _*
      ).provideCustomLayer((nettyClient >>> awsConfig >>> dynamoDb).mapError(TestFailure.die)) @@ sequential,
      suite("with http4s")(
        tests("http4s"): _*
      ).provideCustomLayer((http4sClient >>> awsConfig >>> dynamoDb).mapError(TestFailure.die)) @@ sequential,
      suite("with akka-http")(
        tests("akkahttp"): _*
      ).provideCustomLayer((actorSystem >>> akkaHttpClient >>> awsConfig >>> dynamoDb).mapError(TestFailure.die)) @@ sequential,
    ) @@ sequential
  }
}