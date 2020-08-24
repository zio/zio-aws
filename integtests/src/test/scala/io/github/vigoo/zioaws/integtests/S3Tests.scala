package io.github.vigoo.zioaws.integtests

import java.net.URI

import io.github.vigoo.zioaws.core.config
import io.github.vigoo.zioaws.s3.model._
import io.github.vigoo.zioaws.{http4s, netty, s3}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.environment.TestRandom
import zio.test._
import zio.{ZIO, ZManaged, console, random}

object S3Tests  extends DefaultRunnableSpec {
  val nettyClient = netty.client()
  val http4sClient = http4s.client()
  val awsConfig = config.default
  val s3Client = s3.customized(
    _.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "key")))
      .endpointOverride(new URI("http://localhost:4566"))
  )

  private def testBucket = {
    for {
      _ <- TestRandom.setSeed(scala.util.Random.nextLong())
      bucketName <- generateName
      env <- ZIO.environment[s3.S3]
    } yield ZManaged.make(
      for {
        _ <- s3.createBucket(CreateBucketRequest(
          bucket = bucketName,
        ))
      } yield bucketName
    )(bucketName =>
      s3.deleteBucket(DeleteBucketRequest(bucketName))
        .provide(env)
        .catchAll(error => ZIO.die(error.toThrowable))
        .unit)
  }

  def tests = Seq(
    testM("can create and delete a bucket") {
      // simple request/response calls
      val steps = for {
        bucket <- testBucket
        _ <- bucket.use { bucketName =>
          ZIO.unit
        }
      } yield ()

      assertM(steps.run)(succeeds(isUnit))
    },
    testM("can upload and download items as byte streams") {
      // streaming input and streaming output calls
      val steps = for {
        testData <- random.nextBytes(4096)
        bucket <- testBucket
        key <- generateName
        receivedData <- bucket.use { bucketName =>
          for {
            _ <- console.putStrLn(s"Uploading $key to $bucketName")
            _ <- s3.putObject(PutObjectRequest(
              bucket = bucketName,
              key = key,
            ), ZStream
              .fromIterable(testData)
              .tap(_ => ZIO.succeed(console.putStr(".")))
              .chunkN(1024))
            _ <- console.putStrLn("Downloading")
            getResponse <- s3.getObject(GetObjectRequest(
              bucket = bucketName,
              key = key
            ))
            getStream = getResponse.output
            result <- getStream.runCollect

            _ <- console.putStrLn("Deleting")
            _ <- s3.deleteObject(DeleteObjectRequest(
              bucket = bucketName,
              key = key
            ))

          } yield result
        }
      } yield testData == receivedData

      assertM(steps)(isTrue)
    }
  )

  private def generateName =
    ZIO.foreach((0 to 8).toList) { _ =>
      random.nextIntBetween('a'.toInt, 'z'.toInt).map(_.toChar)
    }.map(_.mkString)


  override def spec = {
    suite("S3")(
      suite("with Netty")(
        tests: _*
      ).provideCustomLayer((nettyClient >>> awsConfig >>> s3Client).mapError(TestFailure.die)),
      suite("with http4s")(
        tests: _*
      ).provideCustomLayer((http4sClient >>> awsConfig >>> s3Client).mapError(TestFailure.die)),
    )
  }
}
