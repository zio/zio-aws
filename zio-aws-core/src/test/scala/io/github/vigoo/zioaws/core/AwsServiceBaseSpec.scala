package io.github.vigoo.zioaws.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CompletableFuture, Executors}
import java.util.function.Consumer

import org.reactivestreams.{Publisher, Subscriber, Subscription}
import software.amazon.awssdk.awscore.eventstream.{DefaultEventStreamResponseHandlerBuilder, EventStreamResponseHandler}
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer, SdkPublisher}
import zio._
import zio.stream.ZStream
import zio.test.environment.TestEnvironment
import zio.test._
import zio.test.Assertion._

import scala.collection.mutable.ArrayBuffer

object AwsServiceBaseSpec extends DefaultRunnableSpec with AwsServiceBase {
  private val threadPool = Executors.newCachedThreadPool()

  override def spec: ZSpec[TestEnvironment, AwsError] = suite("AwsServiceBaseSpec")(
    testM("asyncRequestResponse") {
      val fakeAwsCall: String => CompletableFuture[Int] = { in =>
        val cf = new CompletableFuture[Int]()
        threadPool.submit(new Runnable {
          override def run(): Unit = cf.complete(in.length)
        })
        cf
      }

      for {
        result <- asyncRequestResponse[String, Int](fakeAwsCall)("hello")
      } yield assert(result)(equalTo(5))
    },
    testM("asyncPaginatedRequest") {
      val fakeAwsCall: String => Publisher[Char] = { in =>
        createCharPublisher(in)
      }

      asyncPaginatedRequest[String, Char, Publisher[Char]](fakeAwsCall, identity)("hello")
        .runCollect
        .map { result =>
          assert(result)(equalTo(Chunk('h', 'e', 'l', 'l', 'o'))) 
        }
    },

    testM("asyncRequestOutputStream") {
      val fakeAwsCall: (String, AsyncResponseTransformer[Int, Task[StreamingOutputResult[Int]]]) => CompletableFuture[Task[StreamingOutputResult[Int]]] =
        (in, transformer) => {
          val cf = new CompletableFuture[Task[StreamingOutputResult[Int]]]()
          threadPool.submit(new Runnable {
            override def run(): Unit = {
              transformer.prepare().thenApply { result =>
                transformer.onResponse(in.length)
                transformer.onStream(createStringByteBufferPublisher(in))
                cf.complete(result)
              }
            }
          })
          cf
        }

      for {
        result <- asyncRequestOutputStream(fakeAwsCall)("hello")
        streamResult <- result.output.runCollect.map(_.toVector)
      } yield assert(streamResult)(equalTo("hello".getBytes(StandardCharsets.US_ASCII).toVector)) &&
        assert(result.response)(equalTo(5))
    },

    testM("asyncRequestInputStream") {
      val fakeAwsCall: (Int, AsyncRequestBody) => CompletableFuture[Int] = { (multipler, asyncBody) =>
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

      for {
        result <- asyncRequestInputStream(fakeAwsCall)(2, ZStream.fromIterable("hello".getBytes(StandardCharsets.US_ASCII)))
      } yield assert(result)(equalTo(10))
    },

    testM("asyncRequestInputOutputStream") {
      val fakeAwsCall: (Int, AsyncRequestBody, AsyncResponseTransformer[Int, Task[StreamingOutputResult[Int]]]) => CompletableFuture[Task[StreamingOutputResult[Int]]] = {
        (multipler, asyncBody, transformer) =>

          val cf = new CompletableFuture[Task[StreamingOutputResult[Int]]]()
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
                  transformer.prepare().thenApply { result =>
                    transformer.onResponse(buffer.length)
                    transformer.onStream(createStringByteBufferPublisher(
                      new String(
                        buffer.toVector.flatMap(b => (0 until multipler).toVector.map(_ => b)).toArray,
                        StandardCharsets.US_ASCII)))
                    cf.complete(result)
                  }
                }
              })
            }
          })
          cf
      }


      for {
        result <- asyncRequestInputOutputStream(fakeAwsCall)(2, ZStream.fromIterable("hello".getBytes(StandardCharsets.US_ASCII)))
        streamResult <- result.output.runCollect.map(_.toVector)
      } yield assert(streamResult)(equalTo("hheelllloo".getBytes(StandardCharsets.US_ASCII).toVector)) &&
        assert(result.response)(equalTo(5))
    },

    testM("asyncRequestEventOutputStream") {
      val fakeAwsCall: (String, EventStreamResponseHandler[Int, Char]) => CompletableFuture[Void] = { (in, responseHandler) =>
        val cf = new CompletableFuture[Void]()
        threadPool.submit(new Runnable {
          override def run(): Unit = {
            cf.complete(null.asInstanceOf[Void])
            responseHandler.responseReceived(in.length)
            responseHandler.onEventStream(createCharPublisher(in))
            responseHandler.complete()
          }
        })
        cf
      }

      asyncRequestEventOutputStream[String, Int, EventStreamResponseHandler[Int, Char], Char, Char](
        fakeAwsCall,
        identity)("hello")
        .runCollect
        .map { result =>
          assert(result.mkString)(equalTo("hello"))
        }
    },

    testM("asyncRequestEventInputStream") {
      val fakeAwsCall: (String, Publisher[Char]) => CompletableFuture[String] = {
        (prefix, publisher) =>

        val cf = new CompletableFuture[String]()
        threadPool.submit(new Runnable {
          override def run(): Unit = {
            publisher.subscribe(new Subscriber[Char] {
              val builder = new StringBuilder(prefix)

              override def onSubscribe(s: Subscription): Unit = {
                s.request(100)
              }

              override def onNext(t: Char): Unit = {
                builder.append(t)
              }

              override def onError(t: Throwable): Unit =
                cf.completeExceptionally(t)

              override def onComplete(): Unit =
                cf.complete(builder.toString)
            })
          }
        })
        cf
      }

      for {
        result <- asyncRequestEventInputStream(fakeAwsCall)("hello", ZStream.fromIterable("world"))
      } yield assert(result)(equalTo("helloworld"))
    },

    testM("asyncRequestEventInputOutputStream") {
      val fakeAwsCall: (String, Publisher[Char], EventStreamResponseHandler[Int, Char]) => CompletableFuture[Void] = {
        (in, publisher, responseHandler) =>

        val cf = new CompletableFuture[Void]()
        threadPool.submit(new Runnable {
          override def run(): Unit = {
            publisher.subscribe(new Subscriber[Char] {
              val builder = new StringBuilder(in)

              override def onSubscribe(s: Subscription): Unit = {
                s.request(100)
              }

              override def onNext(t: Char): Unit = {
                builder.append(t)
              }

              override def onError(t: Throwable): Unit =
                cf.completeExceptionally(t)

              override def onComplete(): Unit = {
                cf.complete(null.asInstanceOf[Void])

                responseHandler.responseReceived(builder.length)
                responseHandler.onEventStream(createCharPublisher(builder.toString()))
                responseHandler.complete()
              }
            })
          }
        })
        cf
      }

      asyncRequestEventInputOutputStream[String, Int, Char, EventStreamResponseHandler[Int, Char], Char, Char](
        fakeAwsCall,
        identity)("hello", ZStream.fromIterable("world"))
        .runCollect
        .map { result =>
          assert(result.mkString)(equalTo("helloworld"))
        }
    }
  )

  def createStringByteBufferPublisher(in: String): SdkPublisher[ByteBuffer] =
    new SdkPublisher[ByteBuffer] {
      val idx: AtomicInteger = new AtomicInteger(0)
      val data: Array[Byte] = in.getBytes(StandardCharsets.US_ASCII)

      override def subscribe(s: Subscriber[_ >: ByteBuffer]): Unit = {
        s.onSubscribe(new Subscription {
          override def request(n: Long): Unit = {
            val remaining = data.length - idx.get()
            val toEmit = math.min(n, remaining).toInt
            if (toEmit > 0) {
              for (_ <- 0 until toEmit) {
                val i = idx.getAndIncrement()

                s.onNext(ByteBuffer.wrap(Array(data(i))))

              }
            }
            if (idx.get() == in.length) {
              s.onComplete()
            }
          }

          override def cancel(): Unit =
            s.onComplete()
        })
      }
    }

  private def createCharPublisher(in: String): SdkPublisher[Char] =
    new SdkPublisher[Char] {
      val idx: AtomicInteger = new AtomicInteger(0)

      override def subscribe(s: Subscriber[_ >: Char]): Unit = {
        s.onSubscribe(new Subscription {
          override def request(n: Long): Unit = {
            val remaining = in.length - idx.get()
            val toEmit = math.min(n, remaining).toInt
            if (toEmit > 0) {
              for (_ <- 0 until toEmit) {
                val i = idx.getAndIncrement()
                s.onNext(in(i))
                if (idx.get == in.length) {
                  s.onComplete()
                }
              }
            }
            if (idx.get() == in.length) {
              s.onComplete()
            }
          }

          override def cancel(): Unit =
            s.onComplete()
        })
      }
    }
}
