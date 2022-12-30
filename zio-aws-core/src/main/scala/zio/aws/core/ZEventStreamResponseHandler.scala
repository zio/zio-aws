package zio.aws.core

import org.reactivestreams.Publisher
import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.SdkPublisher
import zio._
import zio.stream.ZStream

object ZEventStreamResponseHandler {
  def create[ResponseT, EventT](
      runtime: Runtime[Any],
      signalQueue: Queue[AwsError],
      responsePromise: Promise[AwsError, ResponseT],
      finishedPromise: Promise[AwsError, Unit],
      promise: Promise[AwsError, Publisher[EventT]]
  ): EventStreamResponseHandler[ResponseT, EventT] =
    new EventStreamResponseHandler[ResponseT, EventT] {
      override def responseReceived(response: ResponseT): Unit =
        Unsafe.unsafe { implicit u =>
          runtime.unsafe
            .run(
              responsePromise.succeed(response)
            )
            .getOrThrowFiberFailure()
        }

      override def onEventStream(publisher: SdkPublisher[EventT]): Unit =
        Unsafe.unsafe { implicit u =>
          runtime.unsafe
            .run(
              promise.succeed(publisher)
            )
            .getOrThrowFiberFailure()
        }

      override def exceptionOccurred(throwable: Throwable): Unit = {
        Unsafe.unsafe { implicit u =>
          runtime.unsafe
            .run {
              val error = AwsError.fromThrowable(throwable)
              signalQueue.offerAll(Seq(error, error)) *>
                finishedPromise.fail(error)
            }
            .getOrThrowFiberFailure()
        }
      }

      override def complete(): Unit = {
        Unsafe.unsafe { implicit u =>
          runtime.unsafe
            .run {
              finishedPromise.succeed(())
              // We cannot signal termination here because the publisher stream may still have buffered items.
              // Completion is marked by the publisher
            }
            .getOrThrowFiberFailure()
        }
      }
    }
}
