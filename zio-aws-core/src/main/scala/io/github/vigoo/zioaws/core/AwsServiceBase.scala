package io.github.vigoo.zioaws.core

import java.util.concurrent.CompletableFuture

import org.reactivestreams.Publisher
import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import zio._
import zio.interop.reactivestreams._
import zio.stream.ZStream
import zio.stream.ZStream.TerminationStrategy

import scala.reflect.ClassTag

trait AwsServiceBase {
  final def asyncRequestResponse[Request, Response](impl: Request => CompletableFuture[Response])(request: Request): IO[AwsError, Response] =
    ZIO.fromCompletionStage(impl(request)).mapError(AwsError.fromThrowable)

  final def asyncPaginatedRequest[Request, Item, Response](impl: Request => Response, selector: Response => Publisher[Item])(request: Request): ZStream[Any, AwsError, Item] =
    ZStream.unwrap {
      ZIO(selector(impl(request)).toStream().mapError(AwsError.fromThrowable)).mapError(AwsError.fromThrowable)
    }

  final def asyncRequestOutputStream[Request, Response](impl: (Request, AsyncResponseTransformer[Response, Task[StreamingOutputResult[Response]]]) => CompletableFuture[Task[StreamingOutputResult[Response]]])
                                                       (request: Request): IO[AwsError, StreamingOutputResult[Response]] = {
    for {
      transformer <- ZStreamAsyncResponseTransformer[Response]()
      streamingOutputResultTask <- ZIO.fromCompletionStage(impl(request, transformer)).mapError(AwsError.fromThrowable)
      streamingOutputResult <- streamingOutputResultTask.mapError(AwsError.fromThrowable)
    } yield streamingOutputResult
  }

  final def asyncRequestInputStream[Request, Response](impl: (Request, AsyncRequestBody) => CompletableFuture[Response])
                                                      (request: Request, body: ZStream[Any, AwsError, Byte]): IO[AwsError, Response] =
    ZIO.runtime.flatMap { implicit runtime: Runtime[Any] =>
      ZIO.fromCompletionStage(impl(request, new ZStreamAsyncRequestBody(body))).mapError(AwsError.fromThrowable)
    }

  final def asyncRequestInputOutputStream[Request, Response](impl: (Request, AsyncRequestBody, AsyncResponseTransformer[Response, Task[StreamingOutputResult[Response]]]) => CompletableFuture[Task[StreamingOutputResult[Response]]])
                                                            (request: Request, body: ZStream[Any, AwsError, Byte]): IO[AwsError, StreamingOutputResult[Response]] = {
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
           createHandler: (EventStreamResponseHandler[Response, EventI]) => ResponseHandler)
          (request: Request)
          (implicit outEventTag: ClassTag[Event]): ZStream[Any, AwsError, Event] = {
    ZStream.unwrap {
      for {
        runtime <- ZIO.runtime[Any]
        publisherPromise <- Promise.make[AwsError, Publisher[EventI]]
        responsePromise <- Promise.make[AwsError, Response]
        signalQueue <- ZQueue.bounded[Option[AwsError]](16)

        _ <- ZIO.fromCompletionStage(impl(request, createHandler(ZEventStreamResponseHandler.create(
          runtime,
          signalQueue,
          responsePromise,
          publisherPromise
        )))).mapError(AwsError.fromThrowable)
        _ <- responsePromise.await
        publisher <- publisherPromise.await

        stream = publisher
          .toStream()
          .mapError(AwsError.fromThrowable)
          .mergeWith(ZStream.fromQueueWithShutdown(signalQueue), TerminationStrategy.Either)(Left.apply, Right.apply)
          .map(_.swap)
          .takeUntil(_.isLeft)
          .flatMap {
            case Left(None) => ZStream.empty
            case Left(Some(error)) => ZStream.fail(error)
            case Right(item: Event) => ZStream.succeed(item)
            case Right(_) => ZStream.empty
          }
      } yield stream
    }
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
              createHandler: (EventStreamResponseHandler[Response, OutEventI]) => ResponseHandler)
             (request: Request, input: ZStream[Any, AwsError, InEvent])
             (implicit outEventTag: ClassTag[OutEvent]): ZStream[Any, AwsError, OutEvent] = {
    ZStream.unwrap {
      for {
        publisher <- input.mapError(_.toThrowable).toPublisher
        runtime <- ZIO.runtime[Any]
        outPublisherPromise <- Promise.make[AwsError, Publisher[OutEventI]]
        responsePromise <- Promise.make[AwsError, Response]
        signalQueue <- ZQueue.bounded[Option[AwsError]](16)

        _ <- ZIO.fromCompletionStage(impl(
          request,
          publisher,
          createHandler(ZEventStreamResponseHandler.create(
            runtime,
            signalQueue,
            responsePromise,
            outPublisherPromise
          )))).mapError(AwsError.fromThrowable)
        outPublisher <- outPublisherPromise.await
        stream = outPublisher
          .toStream()
          .mapError(AwsError.fromThrowable)
          .mergeWith(ZStream.fromQueueWithShutdown(signalQueue), TerminationStrategy.Either)(Left.apply, Right.apply)
          .map(_.swap)
          .takeUntil(_.isLeft)
          .flatMap {
            case Left(None) => ZStream.empty
            case Left(Some(error)) => ZStream.fail(error)
            case Right(item: OutEvent) => ZStream.succeed(item)
            case Right(_) => ZStream.empty
          }
      } yield stream
    }
  }
}
