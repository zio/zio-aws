package io.github.vigoo.zioaws.core.sim

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import org.reactivestreams.{Subscriber, Subscription}
import software.amazon.awssdk.core.async.SdkPublisher
import zio.Chunk

import scala.annotation.tailrec
import scala.collection.mutable

object SimulatedPublisher {

  sealed trait Action

  case object Subscribe extends Action

  case object Emit extends Action

  case object Complete extends Action

  case class Error(failure: Throwable) extends Action

  def correctSequence[InElem](in: Chunk[InElem]): List[Action] =
    Subscribe :: in.indices.map(_ => Emit).toList ::: List(Complete)

  def wrapSubscriber[T](inner: Subscriber[T], simulation: List[Action]): Subscriber[T] =
    new Subscriber[T] {
      private val steps = mutable.Queue.empty[Action]
      simulation.foreach(steps.enqueue)

      @tailrec
      override def onSubscribe(s: Subscription): Unit = {
        if (steps.nonEmpty) {
          steps.dequeue() match {
            case Subscribe => inner.onSubscribe(s)
            case Emit =>
              inner.onError(new RuntimeException(s"Simulation cannot emit before subscribe"))
              onSubscribe(s)
            case Complete =>
              inner.onComplete()
              onSubscribe(s)
            case Error(failure) =>
              inner.onError(failure)
              onSubscribe(s)
          }
        }
      }

      @tailrec
      override def onNext(t: T): Unit = {
        if (steps.nonEmpty) {
          steps.dequeue() match {
            case Subscribe =>
              inner.onError(new RuntimeException(s"Simulation cannot subscribe during emit"))
              onNext(t)
            case Emit =>
              inner.onNext(t)
            case Complete =>
              inner.onComplete()
              onNext(t)
            case Error(failure) =>
              inner.onError(failure)
              onNext(t)
          }
        }
      }

      @tailrec
      override def onError(t: Throwable): Unit = {
        if (steps.nonEmpty) {
          steps.dequeue() match {
            case Subscribe =>
              inner.onError(new RuntimeException(s"Simulation cannot subscribe during error"))
              onError(t)
            case Emit =>
              inner.onError(new RuntimeException(s"Simulation cannot emit during error"))
              onError(t)
            case Complete =>
              inner.onComplete()
              onError(t)
            case Error(failure) =>
              inner.onError(failure)
          }
        }
      }

      @tailrec
      override def onComplete(): Unit = {
        if (steps.nonEmpty) {
          steps.dequeue() match {
            case Subscribe =>
              inner.onError(new RuntimeException(s"Simulation cannot subscribe during error"))
              onComplete()
            case Emit =>
              inner.onError(new RuntimeException(s"Simulation cannot emit during error"))
              onComplete()
            case Complete =>
              inner.onComplete()
            case Error(failure) =>
              inner.onError(failure)
              onComplete()
          }
        }
      }
    }

  def createSimulatedPublisher[InElem, OutElem](in: Chunk[InElem],
                                                convert: InElem => OutElem,
                                                simulation: List[Action]): SdkPublisher[OutElem] =
    new SdkPublisher[OutElem] {
      val idx: AtomicInteger = new AtomicInteger(0)

      override def subscribe(s: Subscriber[_ >: OutElem]): Unit = {
        val wrapped = wrapSubscriber(s, simulation)
        wrapped.onSubscribe(new Subscription {
          override def request(n: Long): Unit = {
            val remaining = in.length - idx.get()
            val toEmit = math.min(n, remaining).toInt
            if (toEmit > 0) {
              for (_ <- 0 until toEmit) {
                val i = idx.getAndIncrement()

                wrapped.onNext(convert(in(i)))

              }
            }
            if (idx.get() == in.length) {
              wrapped.onComplete()
            }
          }

          override def cancel(): Unit =
            wrapped.onComplete()
        })
      }
    }

  def createStringByteBufferPublisher(in: String, simulation: Chunk[Byte] => List[Action] = correctSequence): SdkPublisher[ByteBuffer] = {
    val inChunk = Chunk.fromArray(in.getBytes(StandardCharsets.US_ASCII))
    createSimulatedPublisher[Byte, ByteBuffer](
      inChunk,
      b => ByteBuffer.wrap(Array(b)),
      simulation(inChunk))
  }

  def createCharPublisher(in: String, simulation: Chunk[Char] => List[Action] = correctSequence): SdkPublisher[Char] = {
    val inChunk = Chunk.fromIterable(in)
    createSimulatedPublisher[Char, Char](
      inChunk,
      identity,
      simulation(inChunk))
  }
}