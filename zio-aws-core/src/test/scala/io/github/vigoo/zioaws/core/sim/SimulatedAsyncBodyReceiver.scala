package io.github.vigoo.zioaws.core.sim

import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, ExecutorService}

import org.reactivestreams.{Subscriber, Subscription}
import software.amazon.awssdk.core.async.AsyncRequestBody

object SimulatedAsyncBodyReceiver {
  def useAsyncBody()(implicit threadPool: ExecutorService): (Int, AsyncRequestBody) => CompletableFuture[Int] = { (multipler, asyncBody) =>
        val cf = new CompletableFuture[Int]()
        threadPool.submit(new Runnable {
          override def run(): Unit = {
            asyncBody.subscribe(new Subscriber[ByteBuffer] {
              var sum: Int = 0

              override def onSubscribe(s: Subscription): Unit =
                s.request(100)

              override def onNext(t: ByteBuffer): Unit =
                sum += t.array().length

              override def onError(t: Throwable): Unit =
                cf.completeExceptionally(t)

              override def onComplete(): Unit = {
                cf.complete(multipler * sum)
              }
            })
          }
        })
        cf
      }
}
