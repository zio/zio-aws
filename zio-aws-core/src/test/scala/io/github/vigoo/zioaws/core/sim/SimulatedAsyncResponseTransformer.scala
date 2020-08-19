package io.github.vigoo.zioaws.core.sim

import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, ExecutorService}

import io.github.vigoo.zioaws.core.StreamingOutputResult
import software.amazon.awssdk.core.async.{AsyncResponseTransformer, SdkPublisher}
import zio.Task

object SimulatedAsyncResponseTransformer {
  sealed trait Action
  case object Prepare extends Action
  case object SetResponse extends Action
  case object SetStream extends Action
  case class ReportException(throwable: Throwable) extends Action
  case object CompleteFuture extends Action
  case object FailFuture extends Action

  def useAsyncResponseTransformer[In, Out](in: In,
                                           transformer: AsyncResponseTransformer[Out, Task[StreamingOutputResult[Out]]],
                                           toResult: In => Out,
                                           toPublisher: In => SdkPublisher[ByteBuffer])
                                          (implicit threadPool: ExecutorService): CompletableFuture[Task[StreamingOutputResult[Out]]] = {
    val cf = new CompletableFuture[Task[StreamingOutputResult[Out]]]()
    threadPool.submit(new Runnable {
      override def run(): Unit = {
        transformer.prepare().thenApply { result =>
          transformer.onResponse(toResult(in))
          transformer.onStream(toPublisher(in))
          cf.complete(result)
        }
      }
    })
    cf
  }
}
