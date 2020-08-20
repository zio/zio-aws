package io.github.vigoo.zioaws.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}

import io.github.vigoo.zioaws.core.sim.{SimulatedAsyncBodyReceiver, SimulatedAsyncResponseTransformer, SimulatedPublisher}
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import zio._
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment

import scala.collection.mutable.ArrayBuffer

object AwsServiceBaseSpec extends DefaultRunnableSpec with AwsServiceBase {
  private implicit val threadPool: ExecutorService = Executors.newCachedThreadPool()

  case object SimulatedException extends RuntimeException("simulated")

  override def spec: ZSpec[TestEnvironment, AwsError] = suite("AwsServiceBaseSpec")(
    suite("asyncRequestResponse")(
      testM("success") {
        val fakeAwsCall: String => CompletableFuture[Int] = { in =>
          val cf = new CompletableFuture[Int]()
          threadPool.submit(new Runnable {
            override def run(): Unit = cf.complete(in.length)
          })
          cf
        }

        for {
          result <- asyncRequestResponse[String, Int](fakeAwsCall)("hello")
        } yield assert(result)(equalTo(5))
      },

      testM("failure") {
        val fakeAwsCall: String => CompletableFuture[Int] = { in =>
          val cf = new CompletableFuture[Int]()
          threadPool.submit(new Runnable {
            override def run(): Unit = cf.completeExceptionally(SimulatedException)
          })
          cf
        }

        val call = asyncRequestResponse[String, Int](fakeAwsCall)("hello")
        assertM(call.run)(fails(equalTo(GenericAwsError(SimulatedException))))
      }
    ),
    suite("asyncPaginatedRequest")(
      testM("success")(
        assertM(
          runAsyncPaginatedRequest(SimulatedPublisher.correctSequence)
        )(equalTo(Exit.Success(Chunk('h', 'e', 'l', 'l', 'o'))))
      ),

      testM("fail before subscribe")(
        assertM(runAsyncPaginatedRequest(
          in => SimulatedPublisher.Error(SimulatedException) :: SimulatedPublisher.correctSequence(in)
        ))(isAwsFailure)),
      testM("fail during emit")(
        assertM(runAsyncPaginatedRequest(
          in => SimulatedPublisher.correctSequence(in).splitAt(3) match {
            case (a, b) => a ::: List(SimulatedPublisher.Error(SimulatedException)) ::: b
          }
        ))(isAwsFailure)),
      testM("fail before complete")(
        assertM(runAsyncPaginatedRequest(
          in => SimulatedPublisher.correctSequence(in).init ::: List(SimulatedPublisher.Error(SimulatedException), SimulatedPublisher.Complete)
        ))(isAwsFailure)),
      testM("fail with no complete after")(
        assertM(runAsyncPaginatedRequest(
          in => SimulatedPublisher.correctSequence(in).init ::: List(SimulatedPublisher.Error(SimulatedException))
        ))(isAwsFailure)),
      testM("complete before subscribe is empty result")(
        assertM(runAsyncPaginatedRequest(
          in => SimulatedPublisher.Complete :: SimulatedPublisher.correctSequence(in)
        ))(equalTo(Exit.Success(Chunk.empty))))
    ),

    suite("asyncRequestOutputStream")(
      testM("success")(
        assertM(runAsyncRequestOutput())(
          isCase(
            "Success", {
              case Exit.Success(value) => Some(Exit.Success(value))
              case _ => None
            },
            hasField[Exit.Success[(StreamingOutputResult[Int], Vector[Byte])], Int]("1", _.value._1.response, equalTo(5)) &&
              hasField[Exit.Success[(StreamingOutputResult[Int], Vector[Byte])], Vector[Byte]]("2", _.value._2, equalTo("hello".getBytes(StandardCharsets.US_ASCII).toVector))))),
      testM("future fails before prepare")(
        assertM(runAsyncRequestOutput(SimulatedAsyncResponseTransformer.FailureSpec(failBeforePrepare = Some(SimulatedException))))(
          isAwsFailure
        )),
      testM("report exception on transformer before stream")(
        assertM(runAsyncRequestOutput(SimulatedAsyncResponseTransformer.FailureSpec(failTransformerBeforeStream = Some(SimulatedException))))(
          isAwsFailure
        )),
      testM("report exception on transformer after stream")(
        assertM(runAsyncRequestOutput(SimulatedAsyncResponseTransformer.FailureSpec(failTransformerAfterStream = Some(SimulatedException))))(
          isAwsFailure
        )
      ),
      testM("report exception on stream")(
        assertM(runAsyncRequestOutput(failOnStream = Some(SimulatedException)))(
          isAwsFailure
        )
      ),
      testM("fail future after stream")(
        assertM(runAsyncRequestOutput(SimulatedAsyncResponseTransformer.FailureSpec(failFutureAfterStream = Some(SimulatedException))))(
          isAwsFailure
        )
      ),
    ),
    suite("asyncRequestInputStream")(
      testM("success") {
        val fakeAwsCall = SimulatedAsyncBodyReceiver.useAsyncBody[Int]((in, cf, buffer) => cf.complete(in * buffer.length))

        for {
          result <- asyncRequestInputStream(fakeAwsCall)(2, testByteStream)
        } yield assert(result)(equalTo(10))
      },

      testM("failure on input stream") {
        val fakeAwsCall = SimulatedAsyncBodyReceiver.useAsyncBody[Int]((in, cf, buffer) => cf.complete(in * buffer.length))

        for {
          result <- asyncRequestInputStream(fakeAwsCall)(2, testByteStreamWithFailure).run
        } yield assert(result)(isAwsFailure)
      }
    ),

    suite("asyncRequestInputOutputStream")(
      testM("success")(
        assertM(runAsyncRequestInputOutputRequest())(isCase(
          "Success", {
            case Exit.Success(value) => Some(Exit.Success(value))
            case _ => None
          },
          hasField[Exit.Success[(StreamingOutputResult[Int], Vector[Byte])], Int]("1", _.value._1.response, equalTo(5)) &&
            hasField[Exit.Success[(StreamingOutputResult[Int], Vector[Byte])], Vector[Byte]]("2", _.value._2, equalTo("hheelllloo".getBytes(StandardCharsets.US_ASCII).toVector))))),
      testM("failure on input stream")(
        assertM(runAsyncRequestInputOutputRequest(failOnInput = true))(
          isAwsFailure
        )),
      testM("future fails before prepare")(
        assertM(runAsyncRequestInputOutputRequest(SimulatedAsyncResponseTransformer.FailureSpec(failBeforePrepare = Some(SimulatedException))))(
          isAwsFailure
        )),
      testM("report exception on transformer before stream")(
        assertM(runAsyncRequestInputOutputRequest(SimulatedAsyncResponseTransformer.FailureSpec(failTransformerBeforeStream = Some(SimulatedException))))(
          isAwsFailure
        )),
      testM("report exception on transformer after stream")(
        assertM(runAsyncRequestInputOutputRequest(SimulatedAsyncResponseTransformer.FailureSpec(failTransformerAfterStream = Some(SimulatedException))))(
          isAwsFailure
        )
      ),
      testM("report exception on stream")(
        assertM(runAsyncRequestInputOutputRequest(failOnStream = Some(SimulatedException)))(
          isAwsFailure
        )
      ),
      testM("fail future after stream")(
        assertM(runAsyncRequestInputOutputRequest(SimulatedAsyncResponseTransformer.FailureSpec(failFutureAfterStream = Some(SimulatedException))))(
          isAwsFailure
        )
      ),
    ),

    testM("asyncRequestEventOutputStream") {
      val fakeAwsCall: (String, EventStreamResponseHandler[Int, Char]) => CompletableFuture[Void] = { (in, responseHandler) =>
        val cf = new CompletableFuture[Void]()
        threadPool.submit(new Runnable {
          override def run(): Unit = {
            cf.complete(null.asInstanceOf[Void])
            responseHandler.responseReceived(in.length)
            responseHandler.onEventStream(SimulatedPublisher.createCharPublisher(in))
            responseHandler.complete()
          }
        })
        cf
      }

      asyncRequestEventOutputStream[String, Int, EventStreamResponseHandler[Int, Char], Char, Char](
        fakeAwsCall,
        identity)("hello")
        .runCollect
        .map { result =>
          assert(result.mkString)(equalTo("hello"))
        }
    },

    testM("asyncRequestEventInputStream") {
      val fakeAwsCall: (String, Publisher[Char]) => CompletableFuture[String] = {
        (prefix, publisher) =>

          val cf = new CompletableFuture[String]()
          threadPool.submit(new Runnable {
            override def run(): Unit = {
              publisher.subscribe(new Subscriber[Char] {
                val builder = new StringBuilder(prefix)

                override def onSubscribe(s: Subscription): Unit = {
                  s.request(100)
                }

                override def onNext(t: Char): Unit = {
                  builder.append(t)
                }

                override def onError(t: Throwable): Unit =
                  cf.completeExceptionally(t)

                override def onComplete(): Unit =
                  cf.complete(builder.toString)
              })
            }
          })
          cf
      }

      for {
        result <- asyncRequestEventInputStream(fakeAwsCall)("hello", ZStream.fromIterable("world"))
      } yield assert(result)(equalTo("helloworld"))
    },

    testM("asyncRequestEventInputOutputStream") {
      val fakeAwsCall: (String, Publisher[Char], EventStreamResponseHandler[Int, Char]) => CompletableFuture[Void] = {
        (in, publisher, responseHandler) =>

          val cf = new CompletableFuture[Void]()
          threadPool.submit(new Runnable {
            override def run(): Unit = {
              publisher.subscribe(new Subscriber[Char] {
                val builder = new StringBuilder(in)

                override def onSubscribe(s: Subscription): Unit = {
                  s.request(100)
                }

                override def onNext(t: Char): Unit = {
                  builder.append(t)
                }

                override def onError(t: Throwable): Unit =
                  cf.completeExceptionally(t)

                override def onComplete(): Unit = {
                  cf.complete(null.asInstanceOf[Void])

                  responseHandler.responseReceived(builder.length)
                  responseHandler.onEventStream(SimulatedPublisher.createCharPublisher(builder.toString()))
                  responseHandler.complete()
                }
              })
            }
          })
          cf
      }

      asyncRequestEventInputOutputStream[String, Int, Char, EventStreamResponseHandler[Int, Char], Char, Char](
        fakeAwsCall,
        identity)("hello", ZStream.fromIterable("world"))
        .runCollect
        .map { result =>
          assert(result.mkString)(equalTo("helloworld"))
        }
    }
  )

  private def runAsyncRequestInputOutputRequest(failureSpec: SimulatedAsyncResponseTransformer.FailureSpec = SimulatedAsyncResponseTransformer.FailureSpec(),
                                                failOnInput: Boolean = false,
                                                failOnStream: Option[Throwable] = None): ZIO[Any, AwsError, Exit[AwsError, (StreamingOutputResult[Int], Vector[Byte])]] = {
    val fakeAwsCall: (Int, AsyncRequestBody, AsyncResponseTransformer[Int, Task[StreamingOutputResult[Int]]]) => CompletableFuture[Task[StreamingOutputResult[Int]]] =
      (multipler, asyncBody, transformer) =>
        SimulatedAsyncBodyReceiver.useAsyncBody[Task[StreamingOutputResult[Int]]](
          (in, cf, buffer) => {
            SimulatedAsyncResponseTransformer.useAsyncResponseTransformerImpl[Int, ArrayBuffer[Byte]](
              buffer,
              transformer,
              _.length,
              buf => SimulatedPublisher.createStringByteBufferPublisher(
                new String(
                  buf.toVector.flatMap(b => (0 until in).toVector.map(_ => b)).toArray,
                  StandardCharsets.US_ASCII),
                inChunk => failOnStream match {
                  case Some(throwable) =>
                    SimulatedPublisher.correctSequence(inChunk).splitAt(3) match {
                      case (a, b) => a ::: List(SimulatedPublisher.Error(throwable)) ::: b
                    }
                  case None =>
                    SimulatedPublisher.correctSequence(inChunk)
                }
              ),
              failureSpec,
              cf
            )
          }
        )(threadPool)(multipler, asyncBody)

    val req = for {
      result <- asyncRequestInputOutputStream(fakeAwsCall)(2, if (failOnInput) testByteStreamWithFailure else testByteStream)
      streamResult <- result.output.runCollect.map(_.toVector)
    } yield (result, streamResult)
    req.run
  }

  private def runAsyncPaginatedRequest(simulation: Chunk[Char] => List[SimulatedPublisher.Action]): URIO[Any, Exit[AwsError, Chunk[Char]]] = {
    val fakeAwsCall: String => Publisher[Char] = { in =>
      SimulatedPublisher.createCharPublisher(in, simulation)
    }

    asyncPaginatedRequest[String, Char, Publisher[Char]](fakeAwsCall, identity)("hello")
      .runCollect
      .run
  }

  private def runAsyncRequestOutput(failureSpec: SimulatedAsyncResponseTransformer.FailureSpec = SimulatedAsyncResponseTransformer.FailureSpec(),
                                    failOnStream: Option[Throwable] = None): ZIO[Any, AwsError, Exit[AwsError, (StreamingOutputResult[Int], Vector[Byte])]] = {
    val fakeAwsCall = (in: String, transformer: AsyncResponseTransformer[Int, Task[StreamingOutputResult[Int]]]) =>
      SimulatedAsyncResponseTransformer.useAsyncResponseTransformer[String, Int](
        in,
        transformer,
        _.length,
        in => SimulatedPublisher.createStringByteBufferPublisher(
          in,
          inChunk => failOnStream match {
            case Some(throwable) =>
              SimulatedPublisher.correctSequence(inChunk).splitAt(3) match {
                case (a, b) => a ::: List(SimulatedPublisher.Error(throwable)) ::: b
              }
            case None =>
              SimulatedPublisher.correctSequence(inChunk)
          }
        ),
        failureSpec
      )
    val req = for {
      result <- asyncRequestOutputStream(fakeAwsCall)("hello")
      streamResult <- result.output.runCollect.map(_.toVector)
    } yield (result, streamResult)
    req.run
  }

  private def isAwsFailure[A]: Assertion[Exit[AwsError, A]] = fails(equalTo(GenericAwsError(SimulatedException)))

  private val testByteStream =
    ZStream
      .fromIterable("hello".getBytes(StandardCharsets.US_ASCII))
      .chunkN(2)

  private val testByteStreamWithFailure =
    ZStream
      .fromIterable("hello".getBytes(StandardCharsets.US_ASCII))
      .chunkN(2)
      .concat(ZStream.fail(GenericAwsError(SimulatedException)))
}
