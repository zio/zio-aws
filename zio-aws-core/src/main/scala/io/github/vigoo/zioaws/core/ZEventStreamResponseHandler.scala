package io.github.vigoo.zioaws.core

import org.reactivestreams.Publisher
import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.SdkPublisher
import zio._
import zio.stream.ZStream

object ZEventStreamResponseHandler {
  def create[ResponseT, EventT](runtime: Runtime[Any],
                                signalQueue: Queue[Option[AwsError]],
                                responsePromise: Promise[AwsError, ResponseT],
                                promise: Promise[AwsError, Publisher[EventT]]): EventStreamResponseHandler[ResponseT, EventT] = new EventStreamResponseHandler[ResponseT, EventT] {
    override def responseReceived(response: ResponseT): Unit =
      runtime.unsafeRun(responsePromise.succeed(response))

    override def onEventStream(publisher: SdkPublisher[EventT]): Unit =
      runtime.unsafeRun(promise.succeed(publisher))

    override def exceptionOccurred(throwable: Throwable): Unit =
      runtime.unsafeRun(signalQueue.offer(Some(AwsError.fromThrowable(throwable))))

    override def complete(): Unit = {
      runtime.unsafeRun(signalQueue.offer(None))
    }
  }
}
