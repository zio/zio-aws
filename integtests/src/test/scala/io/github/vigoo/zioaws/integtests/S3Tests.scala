package io.github.vigoo.zioaws.integtests

import java.net.URI
import akka.actor.ActorSystem
import io.github.vigoo.zioaws.core.AwsError
import io.github.vigoo.zioaws.core.aspects._
import io.github.vigoo.zioaws.s3.model._
import io.github.vigoo.zioaws.s3._
import io.github.vigoo.zioaws.core.config._
import io.github.vigoo.zioaws.netty._
import io.github.vigoo.zioaws.http4s._
import io.github.vigoo.zioaws.akkahttp._
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import zio._
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object S3Tests extends DefaultRunnableSpec with Logging {
  val nettyClient = NettyHttpClient.default
  val http4sClient = Http4sClient.default

  val actorSystem =
    ZLayer.fromAcquireRelease(ZIO.attempt(ActorSystem("test")))(sys =>
      ZIO.fromFuture(_ => sys.terminate()).orDie
    )
  val akkaHttpClient = AkkaHttpClient.client()

  val awsConfig = AwsConfig.default
  val s3Client = S3.customized(
    _.credentialsProvider(
      StaticCredentialsProvider
        .create(AwsBasicCredentials.create("dummy", "key"))
    ).region(Region.US_WEST_2)
      .endpointOverride(new URI("http://localhost:4566"))
  ) @@ callLogging

  private def testBucket(prefix: String) = {
    for {
      s3 <- ZIO.service[S3]
      console <- ZIO.service[Console]
      postfix <- Random.nextInt.map(Math.abs)
      bucketName = s"${prefix}-$postfix"
    } yield ZManaged.acquireReleaseWith(
      for {
        _ <- Console.printLine(s"Creating bucket $bucketName").ignore
        _ <- S3.createBucket(
          CreateBucketRequest(
            bucket = bucketName
          )
        )
      } yield bucketName
    )(bucketName =>
      (for {
        _ <- Console.printLine(s"Deleting bucket $bucketName").ignore
        _ <- S3.deleteBucket(DeleteBucketRequest(bucketName))
      } yield ())
        .provideEnvironment(ZEnvironment(s3) ++ ZEnvironment(console))
        .catchAll(error => ZIO.die(error.toThrowable))
        .unit
    )
  }

  def tests(prefix: String, ignoreUpload: Boolean = false) =
    Seq(
      test("can create and delete a bucket") {
        // simple request/response calls
        val steps = for {
          bucket <- testBucket(s"${prefix}-cd")
          _ <- bucket.use { bucketName =>
            ZIO.unit
          }
        } yield ()

        assertM(steps.exit)(succeeds(isUnit))
      } @@ nondeterministic @@ flaky @@ timeout(1.minute),
      test(
        "can upload and download items as byte streams with known content length"
      ) {
        // streaming input and streaming output calls
        val steps = for {
          testData <- Random.nextBytes(65536)
          bucket <- testBucket(s"${prefix}-ud")
          key = "testdata"
          receivedData <- bucket.use { bucketName =>
            for {
              _ <- Console.printLine(s"Uploading $key to $bucketName").ignore
              _ <- S3.putObject(
                PutObjectRequest(
                  bucket = bucketName,
                  key = key,
                  contentLength = Some(
                    65536L
                  ) // Remove to test https://github.com/vigoo/zio-aws/issues/24
                ),
                ZStream
                  .fromIterable(testData)
                  .rechunk(1024)
              )
              _ <- Console.printLine("Downloading").ignore
              getResponse <- S3.getObject(
                GetObjectRequest(
                  bucket = bucketName,
                  key = key
                )
              )
              getStream = getResponse.output
              result <- getStream.runCollect

              _ <- Console.printLine("Deleting").ignore
              _ <- S3.deleteObject(
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
      ) @@ ignore @@ sequential,
      suite("with akka-http")(
        tests("akkahttp", ignoreUpload = true): _*
      ).provideCustomLayer(
        ((Clock.any ++ Console.any ++ (actorSystem >>> akkaHttpClient >>> awsConfig)) >>> s3Client)
          .mapError(TestFailure.die)
      ) @@ sequential
    ) @@ sequential
  }
}
