package io.github.vigoo.zioaws.integtests

import java.net.URI
import akka.actor.ActorSystem
import io.github.vigoo.zioaws.core.{AwsError, config}
import io.github.vigoo.zioaws.core.aspects._
import io.github.vigoo.zioaws.s3.model._
import io.github.vigoo.zioaws.{akkahttp, http4s, netty, s3}
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import zio._
import zio.clock._
import zio.console._
import zio.duration._
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object S3Tests extends DefaultRunnableSpec with Logging {
  val nettyClient = netty.default
  val http4sClient = http4s.default

  val actorSystem =
    ZLayer.fromAcquireRelease(ZIO.effect(ActorSystem("test")))(sys =>
      ZIO.fromFuture(_ => sys.terminate()).orDie
    )
  val akkaHttpClient = akkahttp.client()

  val awsConfig = config.default
  val s3Client = s3.customized(
    _.credentialsProvider(
      StaticCredentialsProvider
        .create(AwsBasicCredentials.create("dummy", "key"))
    ).region(Region.US_WEST_2)
      .endpointOverride(new URI("http://localhost:4566"))
  ) @@ callLogging

  private def testBucket(prefix: String) = {
    for {
      env <- ZIO.environment[s3.S3 with zio.console.Console]
      postfix <- random.nextInt.map(Math.abs)
      bucketName = s"${prefix}-$postfix"
    } yield ZManaged.make(
      for {
        _ <- console.putStrLn(s"Creating bucket $bucketName").ignore
        _ <- s3.createBucket(
          CreateBucketRequest(
            bucket = bucketName
          )
        )
      } yield bucketName
    )(bucketName =>
      (for {
        _ <- console.putStrLn(s"Deleting bucket $bucketName").ignore
        _ <- s3.deleteBucket(DeleteBucketRequest(bucketName))
      } yield ())
        .provide(env)
        .catchAll(error => ZIO.die(error.toThrowable))
        .unit
    )
  }

  def tests(prefix: String, ignoreUpload: Boolean = false) =
    Seq(
      testM("can create and delete a bucket") {
        // simple request/response calls
        val steps = for {
          bucket <- testBucket(s"${prefix}-cd")
          _ <- bucket.use { bucketName =>
            ZIO.unit
          }
        } yield ()

        assertM(steps.run)(succeeds(isUnit))
      } @@ nondeterministic @@ flaky @@ timeout(1.minute),
      testM(
        "can upload and download items as byte streams with known content length"
      ) {
        // streaming input and streaming output calls
        val steps = for {
          testData <- random.nextBytes(65536)
          bucket <- testBucket(s"${prefix}-ud")
          key = "testdata"
          receivedData <- bucket.use { bucketName =>
            for {
              _ <- console.putStrLn(s"Uploading $key to $bucketName").ignore
              _ <- s3.putObject(
                PutObjectRequest(
                  bucket = bucketName,
                  key = key,
                  contentLength = Some(
                    65536L
                  ) // Remove to test https://github.com/vigoo/zio-aws/issues/24
                ),
                ZStream
                  .fromIterable(testData)
                  .chunkN(1024)
              )
              _ <- console.putStrLn("Downloading").ignore
              getResponse <- s3.getObject(
                GetObjectRequest(
                  bucket = bucketName,
                  key = key
                )
              )
              getStream = getResponse.output
              result <- getStream.runCollect

              _ <- console.putStrLn("Deleting").ignore
              _ <- s3.deleteObject(
                DeleteObjectRequest(
                  bucket = bucketName,
                  key = key
                )
              )

            } yield result
          }
        } yield testData == receivedData

        assertM(steps)(isTrue)
      } @@ (if (ignoreUpload) ignore
            else identity) @@ nondeterministic @@ flaky @@ timeout(1.minute)
    )

  override def spec = {
    suite("S3")(
      suite("with Netty")(
        tests("netty"): _*
      ).provideCustomLayer(
        ((Clock.any ++ Console.any ++ (nettyClient >>> awsConfig)) >>> s3Client)
          .mapError(TestFailure.die)
      ) @@ sequential,
      suite("with http4s")(
        tests("http4s"): _*
      ).provideCustomLayer(
        ((Clock.any ++ Console.any ++ (http4sClient >>> awsConfig)) >>> s3Client)
          .mapError(TestFailure.die)
      ) @@ sequential,
      suite("with akka-http")(
        tests("akkahttp", ignoreUpload = true): _*
      ).provideCustomLayer(
        ((Clock.any ++ Console.any ++ (actorSystem >>> akkaHttpClient >>> awsConfig)) >>> s3Client)
          .mapError(TestFailure.die)
      ) @@ sequential
    ) @@ sequential
  }
}
