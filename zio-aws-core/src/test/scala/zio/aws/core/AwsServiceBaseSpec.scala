package zio.aws.core

import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import zio.aws.core.aspects.AwsCallAspect
import zio.aws.core.sim.SimulatedPagination.PaginatedRequest
import zio.aws.core.sim.{
  SimulatedAsyncBodyReceiver,
  SimulatedAsyncResponseTransformer,
  SimulatedEventStreamResponseHandlerReceiver,
  SimulatedPagination,
  SimulatedPublisher
}
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.{
  AsyncRequestBody,
  AsyncResponseTransformer
}
import zio._
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect.{ignore, nonFlaky, sequential, timeout}
import zio.test._

import scala.collection.mutable.ArrayBuffer

trait Service[R] extends AwsServiceBase[R]

object AwsServiceBaseSpec extends ZIOSpecDefault with Service[Any] {
  override val aspect: AwsCallAspect[Any] = AwsCallAspect.identity
  override val serviceName: String = "test"

  private implicit val threadPool: ExecutorService =
    Executors.newCachedThreadPool()

  case object SimulatedException extends RuntimeException("simulated")

  override def spec: Spec[TestEnvironment, AwsError] =
    suite("AwsServiceBaseSpec")(
      suite("asyncRequestResponse")(
        test("success") {
          val fakeAwsCall: String => CompletableFuture[Int] = { in =>
            val cf = new CompletableFuture[Int]()
            threadPool.submit(new Runnable {
              override def run(): Unit = cf.complete(in.length)
            })
            cf
          }

          for {
            result <-
              asyncRequestResponse[String, Int]("test", fakeAwsCall)("hello")
          } yield assert(result)(equalTo(5))
        },
        test("failure") {
          val fakeAwsCall: String => CompletableFuture[Int] = { in =>
            val cf = new CompletableFuture[Int]()
            threadPool.submit(new Runnable {
              override def run(): Unit =
                cf.completeExceptionally(SimulatedException)
            })
            cf
          }

          val call =
            asyncRequestResponse[String, Int]("test", fakeAwsCall)("hello")
          assertZIO(call.exit)(
            fails(equalTo(GenericAwsError(SimulatedException)))
          )
        }
      ),
      suite("asyncJavaPaginatedRequest")(
        test("success")(
          assertZIO(
            runAsyncJavaPaginatedRequest(SimulatedPublisher.correctSequence)
          )(equalTo(Chunk('h', 'e', 'l', 'l', 'o')))
        ),
        test("fail before subscribe")(
          assertZIO(
            runAsyncJavaPaginatedRequest(in =>
              SimulatedPublisher.Error(SimulatedException) :: SimulatedPublisher
                .correctSequence(in)
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/9
        test("fail during emit, no more signals")(
          assertZIO(
            runAsyncJavaPaginatedRequest(in =>
              SimulatedPublisher.correctSequence(in).splitAt(3) match {
                case (a, b) =>
                  a ::: List(SimulatedPublisher.Error(SimulatedException))
              }
            ).exit
          )(isAwsFailure)
        ),
        test("fail during emit")(
          assertZIO(
            runAsyncJavaPaginatedRequest(in =>
              SimulatedPublisher.correctSequence(in).splitAt(3) match {
                case (a, b) =>
                  a ::: List(SimulatedPublisher.Error(SimulatedException)) ::: b
              }
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/7
        test("fail before complete")(
          assertZIO(
            runAsyncJavaPaginatedRequest(in =>
              SimulatedPublisher.correctSequence(in).init ::: List(
                SimulatedPublisher.Error(SimulatedException),
                SimulatedPublisher.Complete
              )
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/7
        test("fail with no complete after")(
          assertZIO(
            runAsyncJavaPaginatedRequest(in =>
              SimulatedPublisher.correctSequence(in).init ::: List(
                SimulatedPublisher.Error(SimulatedException)
              )
            ).exit
          )(isAwsFailure)
        ),
        test("complete before subscribe is empty result")(
          assertZIO(
            runAsyncJavaPaginatedRequest(in =>
              SimulatedPublisher.Complete :: SimulatedPublisher
                .correctSequence(in)
            ).exit
          )(equalTo(Exit.Success(Chunk.empty)))
        ) @@ ignore // Illegal case according to RS Publisher/9
      ),
      suite("asyncSimplePaginatedRequest")(
        test("success")(
          assertZIO(
            runAsyncSimplePaginatedRequest("hello")
          )(equalTo(Chunk('h', 'e', 'l', 'l', 'o')))
        ),
        test("success in single-page case")(
          assertZIO(
            runAsyncSimplePaginatedRequest("x")
          )(equalTo(Chunk('x')))
        ),
        test("fail on first page")(
          assertZIO(
            runAsyncSimplePaginatedRequest("hello", failAfter = Some(0)).exit
          )(isAwsFailure)
        ),
        test("fail on other page")(
          assertZIO(
            runAsyncSimplePaginatedRequest("hello", failAfter = Some(3)).exit
          )(isAwsFailure)
        )
      ),
      suite("asyncPaginatedRequest")(
        test("success")(
          assertZIO(
            runAsyncPaginatedRequest("hello")
          )(equalTo(Chunk('h', 'e', 'l', 'l', 'o')))
        ),
        test("success in single-page case")(
          assertZIO(
            runAsyncPaginatedRequest("x")
          )(equalTo(Chunk('x')))
        ),
        test("fail on first page")(
          assertZIO(
            runAsyncPaginatedRequest("hello", failAfter = Some(0)).exit
          )(isAwsFailure)
        ),
        test("fail on other page")(
          assertZIO(
            runAsyncPaginatedRequest("hello", failAfter = Some(3)).exit
          )(isAwsFailure)
        )
      ),
      suite("asyncRequestOutputStream")(
        test("success")(
          assertZIO(runAsyncRequestOutput())(
            isCase(
              "Success",
              {
                case Exit.Success(value) => Some(Exit.Success(value))
                case _                   => None
              },
              hasField[Exit.Success[
                (StreamingOutputResult[Any, Int, Byte], Vector[Byte])
              ], Int]("1", _.value._1.response, equalTo(5)) &&
                hasField[Exit.Success[
                  (StreamingOutputResult[Any, Int, Byte], Vector[Byte])
                ], Vector[Byte]](
                  "2",
                  _.value._2,
                  equalTo("hello".getBytes(StandardCharsets.US_ASCII).toVector)
                )
            )
          )
        ),
        test("future fails before prepare")(
          assertZIO(
            runAsyncRequestOutput(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failBeforePrepare = Some(SimulatedException))
            )
          )(
            isAwsFailure
          )
        ),
        test("report exception on transformer before stream")(
          assertZIO(
            runAsyncRequestOutput(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failTransformerBeforeStream =
                  Some(SimulatedException)
                )
            )
          )(
            isAwsFailure
          )
        ),
        test("report exception on transformer after stream")(
          assertZIO(
            runAsyncRequestOutput(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failTransformerAfterStream =
                  Some(SimulatedException)
                )
            )
          )(
            isAwsFailure
          )
        ),
        test("report exception on stream")(
          assertZIO(
            runAsyncRequestOutput(failOnStream = Some(SimulatedException))
          )(
            isAwsFailure
          )
        ),
        test("fail future after stream")(
          assertZIO(
            runAsyncRequestOutput(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failFutureAfterStream = Some(SimulatedException))
            )
          )(
            isAwsFailure
          )
        )
      ),
      suite("asyncRequestInputStream")(
        test("success") {
          val fakeAwsCall =
            SimulatedAsyncBodyReceiver.useAsyncBody[Int]((in, cf, buffer) =>
              cf.complete(in * buffer.length)
            )

          for {
            result <-
              asyncRequestInputStream("test", fakeAwsCall)(2, testByteStream)
          } yield assert(result)(equalTo(10))
        },
        test("failure on input stream") {
          val fakeAwsCall =
            SimulatedAsyncBodyReceiver.useAsyncBody[Int]((in, cf, buffer) =>
              cf.complete(in * buffer.length)
            )

          for {
            result <- asyncRequestInputStream("test", fakeAwsCall)(
              2,
              testByteStreamWithFailure
            ).exit
          } yield assert(result)(isAwsFailure)
        }
      ),
      suite("asyncRequestInputOutputStream")(
        test("success")(
          assertZIO(runAsyncRequestInputOutputRequest())(
            hasField[
              (StreamingOutputResult[Any, Int, Byte], Vector[Byte]),
              Int
            ]("1", _._1.response, equalTo(5)) &&
              hasField[
                (StreamingOutputResult[Any, Int, Byte], Vector[Byte]),
                Vector[Byte]
              ](
                "2",
                _._2,
                equalTo(
                  "hheelllloo".getBytes(StandardCharsets.US_ASCII).toVector
                )
              )
          )
        ),
        test("failure on input stream")(
          assertZIO(runAsyncRequestInputOutputRequest(failOnInput = true).exit)(
            isAwsFailure
          )
        ),
        test("future fails before prepare")(
          assertZIO(
            runAsyncRequestInputOutputRequest(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failBeforePrepare = Some(SimulatedException))
            ).exit
          )(
            isAwsFailure
          )
        ),
        test("report exception on transformer before stream")(
          assertZIO(
            runAsyncRequestInputOutputRequest(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failTransformerBeforeStream =
                  Some(SimulatedException)
                )
            ).exit
          )(
            isAwsFailure
          )
        ),
        test("report exception on transformer after stream")(
          assertZIO(
            runAsyncRequestInputOutputRequest(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failTransformerAfterStream =
                  Some(SimulatedException)
                )
            ).exit
          )(
            isAwsFailure
          )
        ),
        test("report exception on stream")(
          assertZIO(
            runAsyncRequestInputOutputRequest(failOnStream =
              Some(SimulatedException)
            ).exit
          )(
            isAwsFailure
          )
        ),
        test("fail future after stream")(
          assertZIO(
            runAsyncRequestInputOutputRequest(
              SimulatedAsyncResponseTransformer
                .FailureSpec(failFutureAfterStream = Some(SimulatedException))
            ).exit
          )(
            isAwsFailure
          )
        )
      ),
      suite("asyncRequestEventOutputStream")(
        test("success") {
          assertZIO(runAsyncRequestEventOutputStream())(equalTo("hello"))
        },
        test("response can go later than stream starts") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventOutputStream(
              handlerSteps = List(
                CompleteFuture,
                EventStream,
                ResponseReceived
              )
            ).map(_.mkString)
          )(equalTo("hello"))
        },
        test("future can be completed later") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventOutputStream(
              handlerSteps = List(
                EventStream,
                ResponseReceived,
                CompleteFuture
              )
            ).map(_.mkString)
          )(equalTo("hello"))
        },
        test("future may not be completed at all") {
          /*
            Not all SDK methods complete on success via the completion stage, for example kinesis subscribeToShard, for
            which the publisherPromise completes first.
           */

          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventOutputStream(
              handlerSteps = List(
                EventStream,
                ResponseReceived
              )
            ).map(_.mkString)
          )(equalTo("hello"))
        },
        test("exception before stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventOutputStream(
              handlerSteps = List(
                ResponseReceived,
                ReportException(SimulatedException),
                EventStream,
                CompleteFuture
              )
            ).exit
          )(isAwsFailure)
        },
        test("exception after stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventOutputStream(
              handlerSteps = List(
                ResponseReceived,
                EventStream,
                ReportException(SimulatedException),
                CompleteFuture
              )
            ).exit
          )(isAwsFailure)
        },
        test("failed future before stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventOutputStream(
              handlerSteps = List(
                FailFuture(SimulatedException),
                ResponseReceived,
                EventStream
              )
            ).exit
          )(isAwsFailure)
        } @@ ignore, // NOTE: we could only guarantee this by waiting for the future to complete but it seems like there are cases (like kinesis subscribeToShard) where it never happens
        test("failed future after stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventOutputStream(
              handlerSteps = List(
                ResponseReceived,
                EventStream,
                FailFuture(SimulatedException)
              )
            ).exit
          )(isAwsFailure)
        } @@ ignore, // NOTE: we could only guarantee this by waiting for the future to complete but it seems like there are cases (like kinesis subscribeToShard) where it never happens
        test("publisher fail before subscribe")(
          assertZIO(
            runAsyncRequestEventOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.Error(
                  SimulatedException
                ) :: SimulatedPublisher.correctSequence(in)
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/9
        test("publisher fail during emit, no more signals")(
          assertZIO(
            runAsyncRequestEventOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).splitAt(3) match {
                  case (a, b) =>
                    a ::: List(
                      SimulatedPublisher.Error(SimulatedException)
                    )
                }
            ).exit
          )(isAwsFailure)
        ),
        test("publisher fail during emit")(
          assertZIO(
            runAsyncRequestEventOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).splitAt(3) match {
                  case (a, b) =>
                    a ::: List(
                      SimulatedPublisher.Error(SimulatedException)
                    ) ::: b
                }
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/7
        test("publisher fail before complete")(
          assertZIO(
            runAsyncRequestEventOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).init ::: List(
                  SimulatedPublisher.Error(SimulatedException),
                  SimulatedPublisher.Complete
                )
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/7
        test("publisher fail with no complete after")(
          assertZIO(
            runAsyncRequestEventOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).init ::: List(
                  SimulatedPublisher.Error(SimulatedException)
                )
            ).exit
          )(isAwsFailure)
        ),
        test("publisher complete before subscribe is empty result")(
          assertZIO(
            runAsyncRequestEventOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.Complete :: SimulatedPublisher
                  .correctSequence(in)
            )
          )(equalTo(""))
        ) @@ ignore // Illegal case according to RS Publisher/9
      ),
      suite("asyncRequestEventInputStream")(
        test("success") {
          assertZIO(runAsyncRequestEventInputStream())(equalTo("helloworld"))
        },
        test("failure on input stream") {
          assertZIO(runAsyncRequestEventInputStream(failInput = true).exit)(
            isAwsFailure
          )
        }
      ),
      suite("asyncRequestEventInputOutputStream")(
        test("success") {
          assertZIO(runAsyncRequestEventInputOutputStream())(
            equalTo("helloworld")
          )
        },
        test("failure on input stream") {
          assertZIO(runAsyncRequestEventInputOutputStream(failInput = true).exit)(
            isAwsFailure
          )
        },
        test("response can go later than stream starts") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              handlerSteps = List(
                CompleteFuture,
                EventStream,
                ResponseReceived
              )
            ).map(_.mkString)
          )(equalTo("helloworld"))
        },
        test("future can be completed later") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              handlerSteps = List(
                EventStream,
                ResponseReceived,
                CompleteFuture
              )
            ).map(_.mkString)
          )(equalTo("helloworld"))
        },
        test("future may not be completed at all") {
          /*
            Not all SDK methods complete on success via the completion stage, for example kinesis subscribeToShard, for
            which the publisherPromise completes first.
           */

          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              handlerSteps = List(
                EventStream,
                ResponseReceived
              )
            ).map(_.mkString)
          )(equalTo("helloworld"))
        },
        test("exception before stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              handlerSteps = List(
                ResponseReceived,
                ReportException(SimulatedException),
                EventStream,
                CompleteFuture
              )
            ).exit
          )(isAwsFailure)
        },
        test("exception after stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              handlerSteps = List(
                ResponseReceived,
                EventStream,
                ReportException(SimulatedException),
                CompleteFuture
              )
            ).exit
          )(isAwsFailure)
        },
        test("failed future before stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              handlerSteps = List(
                FailFuture(SimulatedException),
                ResponseReceived,
                EventStream
              )
            ).exit
          )(isAwsFailure)
        } @@ ignore, // NOTE: we could only guarantee this by waiting for the future to complete but it seems like there are cases (like kinesis subscribeToShard) where it never happens,
        test("failed future after stream") {
          import SimulatedEventStreamResponseHandlerReceiver._
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              handlerSteps = List(
                ResponseReceived,
                EventStream,
                FailFuture(SimulatedException)
              )
            ).exit
          )(isAwsFailure)
        } @@ ignore, // NOTE: we could only guarantee this by waiting for the future to complete but it seems like there are cases (like kinesis subscribeToShard) where it never happens
        test("publisher fail before subscribe")(
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.Error(
                  SimulatedException
                ) :: SimulatedPublisher.correctSequence(in)
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/9
        test("publisher fail during emit, no more signals")(
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).splitAt(3) match {
                  case (a, b) =>
                    a ::: List(
                      SimulatedPublisher.Error(SimulatedException)
                    )
                }
            ).exit
          )(isAwsFailure)
        ),
        test("publisher fail during emit")(
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).splitAt(3) match {
                  case (a, b) =>
                    a ::: List(
                      SimulatedPublisher.Error(SimulatedException)
                    ) ::: b
                }
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/7
        test("publisher fail before complete")(
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).init ::: List(
                  SimulatedPublisher.Error(SimulatedException),
                  SimulatedPublisher.Complete
                )
            ).exit
          )(isAwsFailure)
        ) @@ ignore, // Illegal case according to RS Publisher/7
        test("publisher fail with no complete after")(
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.correctSequence(in).init ::: List(
                  SimulatedPublisher.Error(SimulatedException)
                )
            ).exit
          )(isAwsFailure)
        ),
        test("publisher complete before subscribe is empty result")(
          assertZIO(
            runAsyncRequestEventInputOutputStream(
              publisherSteps = in =>
                SimulatedPublisher.Complete :: SimulatedPublisher
                  .correctSequence(in)
            )
          )(equalTo(""))
        ) @@ ignore // Illegal case according to RS Publisher/9
      )
    ) @@ nonFlaky(25)

  private def runAsyncRequestInputOutputRequest(
      failureSpec: SimulatedAsyncResponseTransformer.FailureSpec =
        SimulatedAsyncResponseTransformer.FailureSpec(),
      failOnInput: Boolean = false,
      failOnStream: Option[Throwable] = None
  ): ZIO[
    Any,
    AwsError,
    (StreamingOutputResult[Any, Int, Byte], Vector[Byte])
  ] = {
    val fakeAwsCall: (
        Int,
        AsyncRequestBody,
        AsyncResponseTransformer[Int, Task[
          StreamingOutputResult[Any, Int, Byte]
        ]]
    ) => CompletableFuture[Task[StreamingOutputResult[Any, Int, Byte]]] =
      (multiplier, asyncBody, transformer) =>
        SimulatedAsyncBodyReceiver
          .useAsyncBody[Task[StreamingOutputResult[Any, Int, Byte]]](
            (in, cf, buffer) => {
              SimulatedAsyncResponseTransformer
                .useAsyncResponseTransformerImpl[Int, ArrayBuffer[Byte]](
                  buffer,
                  transformer,
                  _.length,
                  buf =>
                    SimulatedPublisher.createStringByteBufferPublisher(
                      new String(
                        buf.toVector
                          .flatMap(b => (0 until in).toVector.map(_ => b))
                          .toArray,
                        StandardCharsets.US_ASCII
                      ),
                      inChunk =>
                        failOnStream match {
                          case Some(throwable) =>
                            SimulatedPublisher
                              .correctSequence(inChunk)
                              .splitAt(3) match {
                              case (a, b) =>
                                a ::: List(
                                  SimulatedPublisher.Error(throwable)
                                )
                            }
                          case None =>
                            SimulatedPublisher.correctSequence(inChunk)
                        }
                    ),
                  failureSpec,
                  cf
                )
            }
          )(threadPool)(multiplier, asyncBody)

    for {
      result <- asyncRequestInputOutputStream("test", fakeAwsCall)(
        2,
        if (failOnInput) testByteStreamWithFailure else testByteStream
      )
      streamResult <- result.output.runCollect.map(_.toVector)
    } yield (result, streamResult)
  }

  private def runAsyncJavaPaginatedRequest(
      simulation: Chunk[Char] => List[SimulatedPublisher.Action]
  ): ZIO[Any, AwsError, Chunk[Char]] = {
    val fakeAwsCall: String => Publisher[Char] = { in =>
      SimulatedPublisher.createCharPublisher(in, simulation)
    }

    asyncJavaPaginatedRequest[String, Char, Publisher[Char]](
      "test",
      fakeAwsCall,
      identity
    )("hello").runCollect
  }

  private def runAsyncSimplePaginatedRequest(
      test: String,
      failAfter: Option[Int] = None
  ): ZIO[Any, AwsError, Chunk[Char]] = {
    asyncSimplePaginatedRequest[
      SimulatedPagination.PaginatedRequest,
      SimulatedPagination.PaginatedResult,
      Char
    ](
      "test",
      SimulatedPagination.simplePagination(failAfter, SimulatedException),
      (req, token) => req.copy(token = Some(token)),
      _.next,
      rsp => Chunk.fromIterable(rsp.output)
    )(PaginatedRequest(test, None)).runCollect
  }

  private def runAsyncPaginatedRequest(
      test: String,
      failAfter: Option[Int] = None
  ): ZIO[Any, AwsError, Chunk[Char]] =
    for {
      response <-
        asyncPaginatedRequest[
          SimulatedPagination.PaginatedRequest,
          SimulatedPagination.PaginatedResult,
          Char
        ](
          "test",
          SimulatedPagination.simplePagination(failAfter, SimulatedException),
          (req, token) => req.copy(token = Some(token)),
          _.next,
          rsp => Chunk.fromIterable(rsp.output)
        )(PaginatedRequest(test, None))
      streamResult <- response.output.runCollect
    } yield streamResult

  private def runAsyncRequestOutput(
      failureSpec: SimulatedAsyncResponseTransformer.FailureSpec =
        SimulatedAsyncResponseTransformer.FailureSpec(),
      failOnStream: Option[Throwable] = None
  ): ZIO[Any, AwsError, Exit[
    AwsError,
    (StreamingOutputResult[Any, Int, Byte], Vector[Byte])
  ]] = {
    val fakeAwsCall = (
        in: String,
        transformer: AsyncResponseTransformer[Int, Task[
          StreamingOutputResult[Any, Int, Byte]
        ]]
    ) =>
      SimulatedAsyncResponseTransformer
        .useAsyncResponseTransformer[String, Int](
          in,
          transformer,
          _.length,
          in =>
            SimulatedPublisher.createStringByteBufferPublisher(
              in,
              inChunk =>
                failOnStream match {
                  case Some(throwable) =>
                    SimulatedPublisher
                      .correctSequence(inChunk)
                      .splitAt(3) match {
                      case (a, b) =>
                        a ::: List(SimulatedPublisher.Error(throwable))
                    }
                  case None =>
                    SimulatedPublisher.correctSequence(inChunk)
                }
            ),
          failureSpec
        )
    val req = for {
      result <- asyncRequestOutputStream("test", fakeAwsCall)("hello")
      streamResult <- result.output.runCollect.map(_.toVector)
    } yield (result, streamResult)
    req.exit
  }

  private def runAsyncRequestEventOutputStream(
      publisherSteps: Chunk[Char] => List[SimulatedPublisher.Action] =
        SimulatedPublisher.correctSequence,
      handlerSteps: List[SimulatedEventStreamResponseHandlerReceiver.Action] =
        SimulatedEventStreamResponseHandlerReceiver.defaultSteps
  ): ZIO[Any, AwsError, String] = {
    val fakeAwsCall =
      (in: String, responseHandler: EventStreamResponseHandler[Int, Char]) =>
        SimulatedEventStreamResponseHandlerReceiver
          .useEventStreamResponseHandler[String, Int, Char](
            in,
            responseHandler,
            _.length,
            (in, onComplete) =>
              SimulatedPublisher
                .createCharPublisher(in, publisherSteps, onComplete),
            handlerSteps
          )

    asyncRequestEventOutputStream[String, Int, EventStreamResponseHandler[
      Int,
      Char
    ], Char, Char]("test", fakeAwsCall, identity)("hello").runCollect
      .map(_.mkString)
  }

  private def runAsyncRequestEventInputStream(
      failInput: Boolean = false
  ): IO[AwsError, String] = {
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

    asyncRequestEventInputStream("test", fakeAwsCall)(
      "hello",
      testCharStream(failInput)
    )
  }

  private def testCharStream(failInput: Boolean) = {
    if (failInput) {
      ZStream
        .fromIterable("world")
        .rechunk(2)
        .concat(ZStream.fail(GenericAwsError(SimulatedException)))
    } else {
      ZStream
        .fromIterable("world")
        .rechunk(2)
    }
  }

  private def runAsyncRequestEventInputOutputStream(
      publisherSteps: Chunk[Char] => List[SimulatedPublisher.Action] =
        SimulatedPublisher.correctSequence,
      handlerSteps: List[SimulatedEventStreamResponseHandlerReceiver.Action] =
        SimulatedEventStreamResponseHandlerReceiver.defaultSteps,
      failInput: Boolean = false
  ): ZIO[Any, AwsError, String] = {
    val fakeAwsCall: (
        String,
        Publisher[Char],
        EventStreamResponseHandler[Int, Char]
    ) => CompletableFuture[Void] = { (in, publisher, responseHandler) =>
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
              SimulatedEventStreamResponseHandlerReceiver
                .useEventStreamResponseHandlerImpl[String, Int, Char](
                  cf,
                  builder.toString(),
                  responseHandler,
                  _.length,
                  (in, onComplete) =>
                    SimulatedPublisher
                      .createCharPublisher(in, publisherSteps, onComplete),
                  handlerSteps
                )
            }
          })
        }
      })
      cf
    }

    asyncRequestEventInputOutputStream[
      String,
      Int,
      Char,
      EventStreamResponseHandler[Int, Char],
      Char,
      Char
    ]("test", fakeAwsCall, identity)(
      "hello",
      testCharStream(failInput)
    ).runCollect
      .map(_.mkString)
  }

  private def isAwsFailure[A]: Assertion[Exit[AwsError, A]] =
    fails(equalTo(GenericAwsError(SimulatedException)))

  private val testByteStream =
    ZStream
      .fromIterable("hello".getBytes(StandardCharsets.US_ASCII))
      .rechunk(2)

  private val testByteStreamWithFailure =
    ZStream
      .fromIterable("hello".getBytes(StandardCharsets.US_ASCII))
      .rechunk(2)
      .concat(ZStream.fail(GenericAwsError(SimulatedException)))
}
