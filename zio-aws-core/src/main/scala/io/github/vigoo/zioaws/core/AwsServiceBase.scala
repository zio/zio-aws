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
  final protected def asyncRequestResponse[Request, Response](impl: Request => CompletableFuture[Response])(request: Request): IO[AwsError, Response] =
    ZIO.fromCompletionStage(impl(request)).mapError(AwsError.fromThrowable)

  final protected def asyncJavaPaginatedRequest[Request, Item, Response](impl: Request => Response, selector: Response => Publisher[Item])(request: Request): ZStream[Any, AwsError, Item] =
    ZStream.unwrap {
      ZIO(selector(impl(request)).toStream().mapError(AwsError.fromThrowable)).mapError(AwsError.fromThrowable)
    }

  final protected def asyncSimplePaginatedRequest[Request, Response, Item](impl: Request => CompletableFuture[Response],
                                                                           setNextToken: (Request, String) => Request,
                                                                           getNextToken: Response => Option[String],
                                                                           getItems: Response => Chunk[Item])
                                                                          (request: Request): ZStream[Any, AwsError, Item] =
    ZStream.unwrap {
      ZIO.fromCompletionStage(impl(request)).mapError(AwsError.fromThrowable).flatMap { response =>
        getNextToken(response) match {
          case Some(nextToken) =>
            val stream = ZStream {
              for {
                nextTokenRef <- Ref.make[Option[String]](Some(nextToken)).toManaged_
                pull = for {
                  token <- nextTokenRef.get
                  chunk <- token match {
                    case Some(t) =>
                      for {
                        nextRequest <- ZIO.effect(setNextToken(request, t)).mapError(t => Some(GenericAwsError(t)))
                        rsp <- ZIO.fromCompletionStage(impl(nextRequest)).mapError(t => Some(GenericAwsError(t)))
                        _ <- nextTokenRef.set(getNextToken(rsp))
                      } yield getItems(rsp)
                    case None =>
                      IO.fail(None)
                  }
                } yield chunk
              } yield pull
            }
            ZIO.succeed(stream)
          case None =>
            // No pagination
            ZIO.succeed(ZStream.fromChunk(getItems(response)))
        }
      }
    }

  final protected def asyncPaginatedRequest[Request, Response, Item](impl: Request => CompletableFuture[Response],
                                                                     setNextToken: (Request, String) => Request,
                                                                     getNextToken: Response => Option[String],
                                                                     getItems: Response => Chunk[Item])
                                                                    (request: Request): IO[AwsError, StreamingOutputResult[Response, Item]] = {
      ZIO.fromCompletionStage(impl(request)).mapError(AwsError.fromThrowable).flatMap { response =>
      getNextToken(response) match {
        case Some(nextToken) =>
          val stream = ZStream {
            for {
              nextTokenRef <- Ref.make[Option[String]](Some(nextToken)).toManaged_
              pull = for {
                token <- nextTokenRef.get
                chunk <- token match {
                  case Some(t) =>
                    for {
                      nextRequest <- ZIO.effect(setNextToken(request, t)).mapError(t => Some(GenericAwsError(t)))
                      rsp <- ZIO.fromCompletionStage(impl(nextRequest)).mapError(t => Some(GenericAwsError(t)))
                      _ <- nextTokenRef.set(getNextToken(rsp))
                    } yield getItems(rsp)
                  case None =>
                    IO.fail(None)
                }
              } yield chunk
            } yield pull
          }
          ZIO.succeed(StreamingOutputResult(response, stream))
        case None =>
          // No pagination
          ZIO.succeed(StreamingOutputResult(response, ZStream.fromChunk(getItems(response))))
      }
    }
  }

  final protected def asyncRequestOutputStream[Request, Response](impl: (Request, AsyncResponseTransformer[Response, Task[StreamingOutputResult[Response, Byte]]]) => CompletableFuture[Task[StreamingOutputResult[Response, Byte]]])
                                                                 (request: Request): IO[AwsError, StreamingOutputResult[Response, Byte]] = {
    for {
      transformer <- ZStreamAsyncResponseTransformer[Response]()
      streamingOutputResultTask <- ZIO.fromCompletionStage(impl(request, transformer)).mapError(AwsError.fromThrowable)
      streamingOutputResult <- streamingOutputResultTask.mapError(AwsError.fromThrowable)
    } yield streamingOutputResult
  }

  final protected def asyncRequestInputStream[Request, Response](impl: (Request, AsyncRequestBody) => CompletableFuture[Response])
                                                                (request: Request, body: ZStream[Any, AwsError, Byte]): IO[AwsError, Response] =
    ZIO.runtime.flatMap { implicit runtime: Runtime[Any] =>
      ZIO.fromCompletionStage(impl(request, new ZStreamAsyncRequestBody(body))).mapError(AwsError.fromThrowable)
    }

  final protected def asyncRequestInputOutputStream[Request, Response](impl: (Request, AsyncRequestBody, AsyncResponseTransformer[Response, Task[StreamingOutputResult[Response, Byte]]]) => CompletableFuture[Task[StreamingOutputResult[Response, Byte]]])
                                                                      (request: Request, body: ZStream[Any, AwsError, Byte]): IO[AwsError, StreamingOutputResult[Response, Byte]] = {
    ZIO.runtime.flatMap { implicit runtime: Runtime[Any] =>
      for {
        transformer <- ZStreamAsyncResponseTransformer[Response]()
        streamingOutputResultTask <- ZIO.fromCompletionStage(impl(request, new ZStreamAsyncRequestBody(body), transformer)).mapError(AwsError.fromThrowable)
        streamingOutputResult <- streamingOutputResultTask.mapError(AwsError.fromThrowable)
      } yield streamingOutputResult
    }
  }

  final protected def asyncRequestEventOutputStream[
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

  final protected def asyncRequestEventInputStream[Request, Response, Event](impl: (Request, Publisher[Event]) => CompletableFuture[Response])
                                                                            (request: Request, input: ZStream[Any, AwsError, Event]): IO[AwsError, Response] =
    for {
      publisher <- input.mapError(_.toThrowable).toPublisher
      response <- ZIO.fromCompletionStage(impl(request, publisher)).mapError(AwsError.fromThrowable)
    } yield response

  final protected def asyncRequestEventInputOutputStream[
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
