package io.github.vigoo.zioaws.core

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
      promise: Promise[AwsError, Publisher[EventT]]
  ): EventStreamResponseHandler[ResponseT, EventT] =
    new EventStreamResponseHandler[ResponseT, EventT] {
      override def responseReceived(response: ResponseT): Unit =
        runtime.unsafeRun(responsePromise.succeed(response))

      override def onEventStream(publisher: SdkPublisher[EventT]): Unit =
        runtime.unsafeRun(promise.succeed(publisher))

      override def exceptionOccurred(throwable: Throwable): Unit =
        runtime.unsafeRun {
          val error = AwsError.fromThrowable(throwable)
          signalQueue.offerAll(Seq(error, error))
        }

      override def complete(): Unit = {
        // We cannot signal termination here because the publisher stream may still have buffered items.
        // Completion is marked by the publisher
      }
    }
}
