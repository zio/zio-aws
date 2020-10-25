package io.github.vigoo.zioaws.core

import java.lang
import java.nio.ByteBuffer
import java.util.Optional

import org.reactivestreams.Subscriber
import software.amazon.awssdk.core.async.AsyncRequestBody
import zio.{Chunk, Runtime}
import zio.stream.ZStream
import zio.interop.reactivestreams._

class ZStreamAsyncRequestBody[R](stream: ZStream[R, AwsError, Byte])(implicit runtime: Runtime[R]) extends AsyncRequestBody {
  override def contentLength(): Optional[lang.Long] = Optional.empty()

  override def subscribe(s: Subscriber[_ >: ByteBuffer]): Unit =
    runtime.unsafeRun {
      for {
        (errorP, sink) <- s.toSink[Throwable]
        _ <- stream
          .mapError(_.toThrowable)
          .mapChunks(chunk => Chunk(ByteBuffer.wrap(chunk.toArray)))
          .run(sink)
          .catchAll(errorP.fail)
          .forkDaemon
      } yield ()
    }
}
