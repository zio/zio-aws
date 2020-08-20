package io.github.vigoo.zioaws.core.sim

import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, ExecutorService}

import org.reactivestreams.{Subscriber, Subscription}
import software.amazon.awssdk.core.async.AsyncRequestBody

import scala.collection.mutable.ArrayBuffer

object SimulatedAsyncBodyReceiver {
  def useAsyncBody[Out](completeFn: (Int, CompletableFuture[Out], ArrayBuffer[Byte]) => Unit)
                       (implicit threadPool: ExecutorService): (Int, AsyncRequestBody) => CompletableFuture[Out] = { (in, asyncBody) =>
    val cf = new CompletableFuture[Out]()
    threadPool.submit(new Runnable {
      override def run(): Unit = {
        asyncBody.subscribe(new Subscriber[ByteBuffer] {
          val buffer = new ArrayBuffer[Byte]()

          override def onSubscribe(s: Subscription): Unit =
            s.request(100)

          override def onNext(t: ByteBuffer): Unit =
            buffer.appendAll(t.array())

          override def onError(t: Throwable): Unit =
            cf.completeExceptionally(t)

          override def onComplete(): Unit = {
            completeFn(in, cf, buffer)
          }
        })
      }
    })
    cf
  }
}
