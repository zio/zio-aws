package io.github.vigoo.zioaws.core

import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

import software.amazon.awssdk.core.async.{AsyncResponseTransformer, SdkPublisher}
import zio._
import zio.stream._
import zio.interop.reactivestreams._

class ZStreamAsyncResponseTransformer[Response](resultStreamPromise: Promise[Throwable, Stream[AwsError, Byte]],
                                                responsePromise: Promise[Throwable, Response],
                                                errorPromise: Promise[Throwable, Unit])
                                               (implicit runtime: Runtime[Any])
  extends AsyncResponseTransformer[Response, Task[StreamingOutputResult[Response]]] {

  override def prepare(): CompletableFuture[Task[StreamingOutputResult[Response]]] =
    CompletableFuture.completedFuture {
      for {
        response <- responsePromise.await
        stream <- resultStreamPromise.await
      } yield StreamingOutputResult(response, stream)
    }

  override def onResponse(response: Response): Unit = {
    runtime.unsafeRun(responsePromise.complete(ZIO.succeed(response)))
  }

  override def onStream(publisher: SdkPublisher[ByteBuffer]): Unit = {
    runtime.unsafeRun(resultStreamPromise.complete(ZIO.succeed {
      publisher
        .toStream()
        .interruptWhen(errorPromise)
        .bimap(AwsError.fromThrowable, Chunk.fromByteBuffer)
        .flattenChunks
    }))
  }

  override def exceptionOccurred(error: Throwable): Unit =
    runtime.unsafeRun(errorPromise.fail(error))
}

object ZStreamAsyncResponseTransformer {
  def apply[Response](): UIO[ZStreamAsyncResponseTransformer[Response]] =
    ZIO.runtime.flatMap { implicit runtime: Runtime[Any] =>
      for {
        resultStreamPromise <- Promise.make[Throwable, Stream[AwsError, Byte]]
        responsePromise <- Promise.make[Throwable, Response]
        errorPromise <- Promise.make[Throwable, Unit]
      } yield new ZStreamAsyncResponseTransformer[Response](resultStreamPromise, responsePromise, errorPromise)
    }
}
