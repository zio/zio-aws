package io.github.vigoo.zioaws.core

import java.util.concurrent.CompletableFuture

import org.reactivestreams.Publisher
import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import zio._
import zio.interop.reactivestreams._
import zio.stream.ZStream

trait AwsServiceBase {
  final def asyncRequestResponse[Request, Response](impl: Request => CompletableFuture[Response])(request: Request): IO[AwsError, Response] =
    ZIO.fromCompletionStage(impl(request)).mapError(AwsError.fromThrowable)

  final def asyncPaginatedRequest[Request, Item, Response](impl: Request => Response, selector: Response => Publisher[Item])(request: Request): IO[AwsError, ZStream[Any, AwsError, Item]] =
    ZIO(selector(impl(request)).toStream().mapError(AwsError.fromThrowable)).mapError(AwsError.fromThrowable)

  final def asyncRequestOutputStream[Request, Response](impl: (Request, AsyncResponseTransformer[Response, Task[StreamingOutputResult[Response]]]) => CompletableFuture[Task[StreamingOutputResult[Response]]])
                                                       (request: Request): IO[AwsError, StreamingOutputResult[Response]] = {
    for {
      transformer <- ZStreamAsyncResponseTransformer[Response]()
      streamingOutputResultTask <- ZIO.fromCompletionStage(impl(request, transformer)).mapError(AwsError.fromThrowable)
      streamingOutputResult <- streamingOutputResultTask.mapError(AwsError.fromThrowable)
    } yield streamingOutputResult
  }

  final def asyncRequestInputStream[Request, Response](impl: (Request, AsyncRequestBody) => CompletableFuture[Response])
                                                      (request: Request, body: ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, Response] =
    ZIO.runtime.flatMap { implicit runtime: Runtime[Any] =>
      ZIO.fromCompletionStage(impl(request, new ZStreamAsyncRequestBody(body))).mapError(AwsError.fromThrowable)
    }

  final def asyncRequestInputOutputStream[Request, Response](impl: (Request, AsyncRequestBody, AsyncResponseTransformer[Response, Task[StreamingOutputResult[Response]]]) => CompletableFuture[Task[StreamingOutputResult[Response]]])
                                                            (request: Request, body: ZStream[Any, AwsError, Chunk[Byte]]): IO[AwsError, StreamingOutputResult[Response]] = {
    ZIO.runtime.flatMap { implicit runtime: Runtime[Any] =>
      for {
        transformer <- ZStreamAsyncResponseTransformer[Response]()
        streamingOutputResultTask <- ZIO.fromCompletionStage(impl(request, new ZStreamAsyncRequestBody(body), transformer)).mapError(AwsError.fromThrowable)
        streamingOutputResult <- streamingOutputResultTask.mapError(AwsError.fromThrowable)
      } yield streamingOutputResult
    }
  }

  final def asyncRequestEventOutputStream[
    Request,
    Response,
    ResponseHandler <: EventStreamResponseHandler[Response, EventI],
    EventI,
    Event](impl: (Request, ResponseHandler) => CompletableFuture[Void],
           buildVisitor: Queue[Event] => ResponseHandler)
          (request: Request): IO[AwsError, ZStream[Any, AwsError, Event]] = {
    for {
      queue <- ZQueue.unbounded[Event]
      stream = ZStream
        .fromQueue(queue)
      _ <- ZIO.fromCompletionStage(impl(request, buildVisitor(queue))).mapError(AwsError.fromThrowable)
      _ <- queue.shutdown
    } yield stream
  }

  final def asyncRequestEventInputStream[Request, Response, Event](impl: (Request, Publisher[Event]) => CompletableFuture[Response])
                                                                  (request: Request, input: ZStream[Any, AwsError, Event]): IO[AwsError, Response] =
    for {
      publisher <- input.mapError(_.toThrowable).toPublisher
      response <- ZIO.fromCompletionStage(impl(request, publisher)).mapError(AwsError.fromThrowable)
    } yield response

  final def asyncRequestEventInputOutputStream[
    Request,
    Response,
    InEvent,
    ResponseHandler <: EventStreamResponseHandler[Response, OutEventI],
    OutEventI,
    OutEvent](impl: (Request, Publisher[InEvent], ResponseHandler) => CompletableFuture[Void],
           buildVisitor: Queue[OutEvent] => ResponseHandler)
          (request: Request, input: ZStream[Any, AwsError, InEvent]): IO[AwsError, ZStream[Any, AwsError, OutEvent]] = {
    for {
      publisher <- input.mapError(_.toThrowable).toPublisher
      queue <- ZQueue.unbounded[OutEvent]
      stream = ZStream
        .fromQueue(queue)
      _ <- ZIO.fromCompletionStage(impl(request, publisher, buildVisitor(queue))).mapError(AwsError.fromThrowable)
      _ <- queue.shutdown
    } yield stream
  }
}
