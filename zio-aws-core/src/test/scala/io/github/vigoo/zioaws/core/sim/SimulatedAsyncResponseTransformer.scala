package io.github.vigoo.zioaws.core.sim

import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, ExecutorService}

import io.github.vigoo.zioaws.core.StreamingOutputResult
import software.amazon.awssdk.core.async.{AsyncResponseTransformer, SdkPublisher}
import zio.Task

object SimulatedAsyncResponseTransformer {
  private def slowDown(): Unit = Thread.sleep(100) // slow down a bit to have a better chance to find issues

  case class FailureSpec(failBeforePrepare: Option[Throwable] = None,
                         failTransformerBeforeStream: Option[Throwable] = None,
                         failTransformerAfterStream: Option[Throwable] = None,
                         failFutureAfterStream: Option[Throwable] = None)


  def useAsyncResponseTransformer[In, Out](in: In,
                                           transformer: AsyncResponseTransformer[Out, Task[StreamingOutputResult[Out]]],
                                           toResult: In => Out,
                                           toPublisher: In => SdkPublisher[ByteBuffer],
                                           failureSpec: FailureSpec)
                                          (implicit threadPool: ExecutorService): CompletableFuture[Task[StreamingOutputResult[Out]]] = {
    val cf = new CompletableFuture[Task[StreamingOutputResult[Out]]]()
    threadPool.submit(new Runnable {
      override def run(): Unit = {
        useAsyncResponseTransformerImpl(in, transformer, toResult, toPublisher, failureSpec, cf)
      }
    })
    cf
  }

  def useAsyncResponseTransformerImpl[Out, In](in: In,
                                               transformer: AsyncResponseTransformer[Out, Task[StreamingOutputResult[Out]]],
                                               toResult: In => Out,
                                               toPublisher: In => SdkPublisher[ByteBuffer],
                                               failureSpec: FailureSpec,
                                               cf: CompletableFuture[Task[StreamingOutputResult[Out]]]): Unit = {
    failureSpec.failBeforePrepare match {
      case Some(throwable) =>
        cf.completeExceptionally(throwable)
      case None =>
        slowDown()
        transformer.prepare().thenApply[Boolean] { (result: Task[StreamingOutputResult[Out]]) =>
          slowDown()
          transformer.onResponse(toResult(in))

          slowDown()
          failureSpec.failTransformerBeforeStream.foreach { throwable =>
            transformer.exceptionOccurred(throwable)
          }
          slowDown()
          transformer.onStream(toPublisher(in))

          failureSpec.failTransformerAfterStream.foreach { throwable =>
            transformer.exceptionOccurred(throwable)
          }

          slowDown()
          failureSpec.failFutureAfterStream match {
            case Some(throwable) =>
              cf.completeExceptionally(throwable)
            case None =>
              cf.complete(result)
          }
        }
    }
  }
}
