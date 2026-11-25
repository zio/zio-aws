package zio.aws.core

import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

import software.amazon.awssdk.core.async.{
  AsyncResponseTransformer,
  SdkPublisher
}
import zio._
import zio.stream._
import zio.interop.reactivestreams._

/** An AWS SDK `AsyncResponseTransformer` that turns a streaming response into a
  * [[StreamingOutputResult]] of bytes. Create one with the companion's `apply`.
  *
  * `prepare()` returns an already completed future that holds a task. The task
  * waits until the SDK has called both `onResponse` and `onStream`, then
  * succeeds with the response and the byte stream. When `exceptionOccurred` is
  * called before `onStream`, the task fails with that exception once `onStream`
  * is called; until then it keeps waiting. When `exceptionOccurred` is called
  * while the stream runs, the stream stops and fails with the exception wrapped
  * by `AwsError.fromThrowable`; errors of the SDK publisher are wrapped the
  * same way.
  */
class ZStreamAsyncResponseTransformer[R, Response](
    resultStreamPromise: Promise[Throwable, Stream[AwsError, Byte]],
    responsePromise: Promise[Throwable, Response],
    errorPromise: Promise[Throwable, Unit]
)(implicit runtime: Runtime[R])
    extends AsyncResponseTransformer[Response, Task[
      StreamingOutputResult[R, Response, Byte]
    ]] {

  override def prepare()
      : CompletableFuture[Task[StreamingOutputResult[R, Response, Byte]]] =
    CompletableFuture.completedFuture {
      for {
        response <- responsePromise.await
        stream <- resultStreamPromise.await
      } yield StreamingOutputResult(response, stream)
    }

  override def onResponse(response: Response): Unit =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(responsePromise.complete(ZIO.succeed(response)))
        .getOrThrowFiberFailure()
    }

  override def onStream(publisher: SdkPublisher[ByteBuffer]): Unit =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe
        .run(resultStreamPromise.complete(errorPromise.poll.flatMap { opt =>
          opt.getOrElse(ZIO.unit) *> ZIO.attempt(
            publisher
              .toZIOStream()
              .interruptWhen(errorPromise)
              .map(Chunk.fromByteBuffer)
              .flattenChunks
              .concat(
                ZStream.fromZIOOption[Any, Throwable, Byte](
                  errorPromise.poll.flatMap {
                    case None    => ZIO.fail(None)
                    case Some(p) => p.mapError(Some.apply) *> ZIO.fail(None)
                  }
                )
              )
              .mapError(AwsError.fromThrowable)
          )
        }))
        .getOrThrowFiberFailure()
    }

  override def exceptionOccurred(error: Throwable): Unit =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(errorPromise.fail(error)).getOrThrowFiberFailure()
    }
}

/** Creates [[ZStreamAsyncResponseTransformer]] instances. */
object ZStreamAsyncResponseTransformer {

  /** Returns a new transformer with new promises, which runs the SDK callbacks
    * with the runtime of the effect that creates it.
    */
  def apply[R, Response]()
      : ZIO[R, Nothing, ZStreamAsyncResponseTransformer[R, Response]] =
    ZIO.runtime.flatMap { implicit runtime: Runtime[R] =>
      for {
        resultStreamPromise <- Promise.make[Throwable, Stream[AwsError, Byte]]
        responsePromise <- Promise.make[Throwable, Response]
        errorPromise <- Promise.make[Throwable, Unit]
      } yield new ZStreamAsyncResponseTransformer[R, Response](
        resultStreamPromise,
        responsePromise,
        errorPromise
      )
    }
}
