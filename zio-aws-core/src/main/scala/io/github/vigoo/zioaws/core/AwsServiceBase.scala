package io.github.vigoo.zioaws.core

import java.util.concurrent.CompletableFuture

import io.github.vigoo.zioaws.core.aspects._
import org.reactivestreams.Publisher
import software.amazon.awssdk.awscore.eventstream.EventStreamResponseHandler
import software.amazon.awssdk.core.async.{
  AsyncRequestBody,
  AsyncResponseTransformer
}
import zio._
import zio.interop.reactivestreams._
import zio.stream.ZStream
import zio.stream.ZStream.TerminationStrategy

import scala.reflect.ClassTag

trait AwsServiceBase[R, Self[_]] {
  val aspect: AwsCallAspect[R]
  val serviceName: String

  final protected def asyncRequestResponse[Request, Response](
      opName: String,
      impl: Request => CompletableFuture[Response]
  )(request: Request): ZIO[R, AwsError, Response] =
    aspect(
      ZIO
        .fromCompletionStage(impl(request))
        .mapError(AwsError.fromThrowable) ? (serviceName / opName)
    ).unwrap

  final protected def asyncJavaPaginatedRequest[Request, Item, Response](
      opName: String,
      impl: Request => Response,
      selector: Response => Publisher[Item]
  )(request: Request): ZStream[R, AwsError, Item] =
    ZStream.unwrap {
      aspect(
        ZIO(selector(impl(request)).toStream().mapError(AwsError.fromThrowable))
          .mapError(AwsError.fromThrowable) ? (serviceName / opName)
      ).unwrap
    }

  final protected def asyncSimplePaginatedRequest[Request, Response, Item](
      opName: String,
      impl: Request => CompletableFuture[Response],
      setNextToken: (Request, String) => Request,
      getNextToken: Response => Option[String],
      getItems: Response => Chunk[Item]
  )(request: Request): ZStream[R, AwsError, Item] =
    ZStream.unwrap {
      aspect(
        ZIO
          .fromCompletionStage(impl(request))
          .mapError(AwsError.fromThrowable) ? (serviceName / opName)
      ).unwrap.flatMap { response =>
        getNextToken(response) match {
          case Some(nextToken) =>
            val stream = ZStream {
              for {
                nextTokenRef <-
                  Ref.make[Option[String]](Some(nextToken)).toManaged
                pull = for {
                  token <- nextTokenRef.get
                  chunk <- token match {
                    case Some(t) =>
                      for {
                        nextRequest <-
                          ZIO
                            .attempt(setNextToken(request, t))
                            .mapError(t => Some(GenericAwsError(t)))
                        rsp <- aspect(
                          ZIO
                            .fromCompletionStage(impl(nextRequest))
                            .mapError(t =>
                              GenericAwsError(t)
                            ) ? (serviceName / opName)
                        ).unwrap.mapError(Some.apply)
                        _ <- nextTokenRef.set(getNextToken(rsp))
                      } yield getItems(rsp)
                    case None =>
                      ZIO.fail(None)
                  }
                } yield chunk
              } yield pull
            }
            ZIO.succeed(ZStream.fromChunk(getItems(response)).concat(stream))
          case None =>
            // No pagination
            ZIO.succeed(ZStream.fromChunk(getItems(response)))
        }
      }
    }

  final protected def asyncPaginatedRequest[Request, Response, Item](
      opName: String,
      impl: Request => CompletableFuture[Response],
      setNextToken: (Request, String) => Request,
      getNextToken: Response => Option[String],
      getItems: Response => Chunk[Item]
  )(
      request: Request
  ): ZIO[R, AwsError, StreamingOutputResult[R, Response, Item]] = {
    aspect(
      ZIO
        .fromCompletionStage(impl(request))
        .mapError(AwsError.fromThrowable) ? (serviceName / opName)
    ).unwrap.flatMap { response =>
      getNextToken(response) match {
        case Some(nextToken) =>
          val stream = ZStream {
            for {
              nextTokenRef <-
                Ref.make[Option[String]](Some(nextToken)).toManaged
              pull = for {
                token <- nextTokenRef.get
                chunk <- token match {
                  case Some(t) =>
                    for {
                      nextRequest <-
                        ZIO
                          .attempt(setNextToken(request, t))
                          .mapError(t => Some(GenericAwsError(t)))
                      rsp <- aspect(
                        ZIO
                          .fromCompletionStage(impl(nextRequest))
                          .mapError(t =>
                            GenericAwsError(t)
                          ) ? (serviceName / opName)
                      ).unwrap.mapError(Some.apply)
                      _ <- nextTokenRef.set(getNextToken(rsp))
                    } yield getItems(rsp)
                  case None =>
                    ZIO.fail(None)
                }
              } yield chunk
            } yield pull
          }
          ZIO.succeed(
            StreamingOutputResult(
              response,
              ZStream.fromChunk(getItems(response)).concat(stream)
            )
          )
        case None =>
          // No pagination
          ZIO.succeed(
            StreamingOutputResult(
              response,
              ZStream.fromChunk(getItems(response))
            )
          )
      }
    }
  }

  final protected def asyncRequestOutputStream[Request, Response](
      opName: String,
      impl: (
          Request,
          AsyncResponseTransformer[Response, Task[
            StreamingOutputResult[R, Response, Byte]
          ]]
      ) => CompletableFuture[Task[StreamingOutputResult[R, Response, Byte]]]
  )(
      request: Request
  ): ZIO[R, AwsError, StreamingOutputResult[R, Response, Byte]] = {
    for {
      transformer <- ZStreamAsyncResponseTransformer[R, Response]()
      streamingOutputResultTask <- aspect(
        ZIO
          .fromCompletionStage(impl(request, transformer))
          .mapError(AwsError.fromThrowable) ? (serviceName / opName)
      ).unwrap
      streamingOutputResult <-
        streamingOutputResultTask.mapError(AwsError.fromThrowable)
    } yield streamingOutputResult
  }

  final protected def asyncRequestInputStream[Request, Response](
      opName: String,
      impl: (Request, AsyncRequestBody) => CompletableFuture[Response]
  )(
      request: Request,
      body: ZStream[R, AwsError, Byte]
  ): ZIO[R, AwsError, Response] =
    ZIO.runtime.flatMap { implicit runtime: Runtime[R] =>
      aspect(
        ZIO
          .fromCompletionStage(
            impl(request, new ZStreamAsyncRequestBody[R](body))
          )
          .mapError(AwsError.fromThrowable) ? (serviceName / opName)
      ).unwrap
    }

  final protected def asyncRequestInputOutputStream[Request, Response](
      opName: String,
      impl: (
          Request,
          AsyncRequestBody,
          AsyncResponseTransformer[Response, Task[
            StreamingOutputResult[R, Response, Byte]
          ]]
      ) => CompletableFuture[Task[StreamingOutputResult[R, Response, Byte]]]
  )(
      request: Request,
      body: ZStream[R, AwsError, Byte]
  ): ZIO[R, AwsError, StreamingOutputResult[R, Response, Byte]] = {
    ZIO.runtime.flatMap { implicit runtime: Runtime[R] =>
      for {
        transformer <- ZStreamAsyncResponseTransformer[R, Response]()
        streamingOutputResultTask <- aspect(
          ZIO
            .fromCompletionStage(
              impl(request, new ZStreamAsyncRequestBody(body), transformer)
            )
            .mapError(AwsError.fromThrowable) ? (serviceName / opName)
        ).unwrap
        streamingOutputResult <-
          streamingOutputResultTask.mapError(AwsError.fromThrowable)
      } yield streamingOutputResult
    }
  }

  final protected def asyncRequestEventOutputStream[
      Request,
      Response,
      ResponseHandler <: EventStreamResponseHandler[Response, EventI],
      EventI,
      Event
  ](
      opName: String,
      impl: (Request, ResponseHandler) => CompletableFuture[Void],
      createHandler: (
          EventStreamResponseHandler[Response, EventI]
      ) => ResponseHandler
  )(
      request: Request
  )(implicit outEventTag: ClassTag[Event]): ZStream[R, AwsError, Event] = {
    ZStream.unwrap {
      for {
        runtime <- ZIO.runtime[Any]
        publisherPromise <- Promise.make[AwsError, Publisher[EventI]]
        responsePromise <- Promise.make[AwsError, Response]
        finishedPromise <- Promise.make[AwsError, Unit]
        signalQueue <- ZQueue.bounded[AwsError](16)

        _ <- aspect(
          ZIO
            .fromCompletionStage(
              impl(
                request,
                createHandler(
                  ZEventStreamResponseHandler.create(
                    runtime,
                    signalQueue,
                    responsePromise,
                    finishedPromise,
                    publisherPromise
                  )
                )
              )
            )
            .mapError(AwsError.fromThrowable) ? (serviceName / opName)
        ).forkWithErrorHandler(error =>
          signalQueue.offerAll(Seq(error, error)) *> finishedPromise.fail(error)
        )

        failOnErrorSignal = signalQueue.take
          .map(Left.apply[AwsError, Unit])
          .absolve
        // NOTE: even though receiveResponse's documentation states it is called before the publisher is set,
        // NOTE: this is not true with EventStreamAsyncResponseTransformer for example in case of Kinesis subscribeToShard
        _ <- publisherPromise.await raceFirst failOnErrorSignal
        publisher <- publisherPromise.await

        stream =
          publisher
            .toStream()
            .mapError(AwsError.fromThrowable)
            .mergeWith(
              ZStream.fromQueue(signalQueue),
              TerminationStrategy.Either
            )(Left.apply, Right.apply)
            .map(_.swap)
            .takeUntil(_.isLeft)
            .flatMap {
              case Left(error)        => ZStream.fail(error)
              case Right(item: Event) => ZStream.succeed(item)
              case Right(_)           => ZStream.empty
            } ++ ZStream.unwrap {
            finishedPromise.await *> signalQueue.poll.map {
              case None          => ZStream.empty
              case Some(failure) => ZStream.fail(failure)
            }
          }
      } yield stream
    }
  }

  final protected def asyncRequestEventInputStream[Request, Response, Event](
      opName: String,
      impl: (Request, Publisher[Event]) => CompletableFuture[Response]
  )(
      request: Request,
      input: ZStream[R, AwsError, Event]
  ): ZIO[R, AwsError, Response] =
    for {
      publisher <- input.mapError(_.toThrowable).toPublisher
      response <- aspect(
        ZIO
          .fromCompletionStage(impl(request, publisher))
          .mapError(AwsError.fromThrowable) ? (serviceName / opName)
      ).unwrap
    } yield response

  final protected def asyncRequestEventInputOutputStream[
      Request,
      Response,
      InEvent,
      ResponseHandler <: EventStreamResponseHandler[Response, OutEventI],
      OutEventI,
      OutEvent
  ](
      opName: String,
      impl: (
          Request,
          Publisher[InEvent],
          ResponseHandler
      ) => CompletableFuture[Void],
      createHandler: (
          EventStreamResponseHandler[Response, OutEventI]
      ) => ResponseHandler
  )(request: Request, input: ZStream[R, AwsError, InEvent])(implicit
      outEventTag: ClassTag[OutEvent]
  ): ZStream[R, AwsError, OutEvent] = {
    ZStream.unwrap {
      for {
        publisher <- input.mapError(_.toThrowable).toPublisher
        runtime <- ZIO.runtime[Any]
        outPublisherPromise <- Promise.make[AwsError, Publisher[OutEventI]]
        responsePromise <- Promise.make[AwsError, Response]
        finishedPromise <- Promise.make[AwsError, Unit]
        signalQueue <- ZQueue.bounded[AwsError](16)

        _ <- aspect(
          ZIO
            .fromCompletionStage(
              impl(
                request,
                publisher,
                createHandler(
                  ZEventStreamResponseHandler.create(
                    runtime,
                    signalQueue,
                    responsePromise,
                    finishedPromise,
                    outPublisherPromise
                  )
                )
              )
            )
            .mapError(AwsError.fromThrowable) ? (serviceName / opName)
        ).forkWithErrorHandler(error =>
          signalQueue.offerAll(Seq(error, error)).unit *> finishedPromise.fail(error)
        )

        failOnErrorSignal = signalQueue.take
          .map(Left.apply[AwsError, Unit])
          .absolve
        // NOTE: even though receiveResponse's documentation states it is called before the publisher is set,
        // NOTE: this is not true with EventStreamAsyncResponseTransformer for example in case of Kinesis subscribeToShard
        _ <- outPublisherPromise.await raceFirst failOnErrorSignal
        outPublisher <- outPublisherPromise.await

        stream =
          outPublisher
            .toStream()
            .mapError(AwsError.fromThrowable)
            .mergeWith(
              ZStream.fromQueue(signalQueue),
              TerminationStrategy.Either
            )(Left.apply, Right.apply)
            .map(_.swap)
            .takeUntil(_.isLeft)
            .flatMap {
              case Left(error)           => ZStream.fail(error)
              case Right(item: OutEvent) => ZStream.succeed(item)
              case Right(_)              => ZStream.empty
            } ++ ZStream.unwrap {
            finishedPromise.await *> signalQueue.poll.map {
              case None          => ZStream.empty
              case Some(failure) => ZStream.fail(failure)
            }
          }
      } yield stream
    }
  }
}
