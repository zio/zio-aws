package io.github.vigoo.zioaws.core.sim

import java.util.concurrent.{CompletableFuture, ExecutorService}

import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.SdkPublisher

object SimulatedEventStreamResponseHandlerReceiver {
  private def slowDown(): Unit =
    Thread.sleep(100) // slow down a bit to have a better chance to find issues

  sealed trait Action

  case object CompleteFuture extends Action

  case class FailFuture(throwable: Throwable) extends Action

  case object ResponseReceived extends Action

  case object EventStream extends Action

  case class ReportException(throwable: Throwable) extends Action

  val defaultSteps = List(
    CompleteFuture,
    ResponseReceived,
    EventStream
  )

  def useEventStreamResponseHandler[In, Response, Out](
      in: In,
      responseHandler: EventStreamResponseHandler[Response, Out],
      toResponse: In => Response,
      toPublisher: (In, () => Unit) => SdkPublisher[Out],
      steps: List[Action]
  )(implicit threadPool: ExecutorService): CompletableFuture[Void] = {
    val cf = new CompletableFuture[Void]()
    threadPool.submit(new Runnable {
      override def run(): Unit = {
        useEventStreamResponseHandlerImpl(
          cf,
          in,
          responseHandler,
          toResponse,
          toPublisher,
          steps
        )
      }
    })
    cf
  }

  def useEventStreamResponseHandlerImpl[In, Response, Out](
      cf: CompletableFuture[Void],
      in: In,
      responseHandler: EventStreamResponseHandler[Response, Out],
      toResponse: In => Response,
      toPublisher: (In, () => Unit) => SdkPublisher[Out],
      steps: List[Action]
  ): Unit = {
    steps.foreach {
      case CompleteFuture =>
        slowDown()
        cf.complete(null.asInstanceOf[Void])
      case FailFuture(throwable) =>
        cf.completeExceptionally(throwable)
      case ResponseReceived =>
        slowDown()
        responseHandler.responseReceived(toResponse(in))
      case EventStream =>
        slowDown()
        val publisher = toPublisher(
          in,
          () => {
            responseHandler.complete()
          }
        )
        responseHandler.onEventStream(publisher)
      case ReportException(throwable) =>
        responseHandler.exceptionOccurred(throwable)
    }
  }
}
