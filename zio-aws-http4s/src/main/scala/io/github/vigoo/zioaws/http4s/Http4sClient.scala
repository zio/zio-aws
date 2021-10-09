package io.github.vigoo.zioaws.http4s

import java.util.concurrent.CompletableFuture
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime
import fs2._
import fs2.interop.reactivestreams._
import _root_.io.github.vigoo.zioaws.core.httpclient._
import org.http4s._
import org.http4s.blaze.channel.{ChannelOptions, OptionValue}
import org.http4s.client._
import org.http4s.blaze.client._
import software.amazon.awssdk.http.async.{
  AsyncExecuteRequest,
  SdkAsyncHttpClient,
  SdkAsyncHttpResponseHandler,
  SdkHttpContentPublisher
}
import software.amazon.awssdk.http.{
  SdkHttpMethod,
  SdkHttpRequest,
  SdkHttpResponse
}
import software.amazon.awssdk.utils.AttributeMap
import zio._
import zio.interop.catz._

import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters._
import org.typelevel.ci.CIString

import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.SSLContext
import scala.util.control.NonFatal

class Http4sClient(client: Client[Task], closeFn: () => Unit)(implicit
    runtime: Runtime[Has[Clock]]
) extends SdkAsyncHttpClient {

  import Http4sClient._

  override def execute(
      request: AsyncExecuteRequest
  ): CompletableFuture[Void] = {
    runtime
      .unsafeRunToFuture(
        client
          .run(
            toHttp4sRequest(
              request.request(),
              request.requestContentPublisher()
            )
          )
          .use(processResponse(_, request.responseHandler()))
      )
      .toJava
      .toCompletableFuture
  }

  override def close(): Unit = {
    closeFn()
  }

  private def toMethod(method: SdkHttpMethod): ExtendedMethod =
    method match {
      case SdkHttpMethod.GET  => ExtendedMethod(Method.GET, canHaveBody = true)
      case SdkHttpMethod.POST => ExtendedMethod(Method.POST, canHaveBody = true)
      case SdkHttpMethod.PUT  => ExtendedMethod(Method.PUT, canHaveBody = true)
      case SdkHttpMethod.DELETE =>
        ExtendedMethod(Method.DELETE, canHaveBody = true)
      case SdkHttpMethod.HEAD => ExtendedMethod(Method.HEAD, canHaveBody = true)
      case SdkHttpMethod.PATCH =>
        ExtendedMethod(Method.PATCH, canHaveBody = true)
      case SdkHttpMethod.OPTIONS =>
        ExtendedMethod(Method.OPTIONS, canHaveBody = true)
    }

  private def toHeaders(
      name: String,
      values: Iterable[String]
  ): List[Header.ToRaw] = {
    if (name == "Expect" && values.toSet == Set("100-continue")) {
      List.empty // skipping
    } else {
      values
        .map(value => Header.ToRaw.rawToRaw(Header.Raw(CIString(name), value)))
        .toList
    }
  }

  private def toEntity(
      method: ExtendedMethod,
      publisher: SdkHttpContentPublisher
  ): EntityBody[Task] =
    if (method.canHaveBody) {
      publisher
        .toStream(asyncRuntimeInstance)
        .map(fs2.Chunk.byteBuffer)
        .flatMap(Stream.chunk)
    } else {
      EmptyBody
    }

  private def toHttp4sRequest(
      request: SdkHttpRequest,
      contentPublisher: SdkHttpContentPublisher
  ): Request[Task] = {
    val method = toMethod(request.method())
    Request(
      method = method.value,
      uri = Uri.unsafeFromString(request.getUri.toString),
      httpVersion = HttpVersion.`HTTP/1.1`,
      headers = Headers(
        request
          .headers()
          .asScala
          .toList
          .flatMap { case (name, hdrs) => toHeaders(name, hdrs.asScala) }: _*
      ),
      body = toEntity(method, contentPublisher)
    )
  }

  private def processResponse(
      response: Response[Task],
      handler: SdkAsyncHttpResponseHandler
  ): Task[Void] = {
    for {
      _ <- Task(
        handler.onHeaders(
          SdkHttpResponse
            .builder()
            .headers(
              response.headers.headers.toList
                .groupBy(_.name)
                .map { case (name, values) =>
                  (name.toString, values.map(_.value).asJava)
                }
                .asJava
            )
            .statusCode(response.status.code)
            .statusText(response.status.reason)
            .build()
        )
      )
      streamFinished <- Promise.make[Throwable, Unit]
      _ <- response.body.chunks
        .map(_.toByteBuffer)
        .onFinalizeCase {
          case Resource.ExitCase.Succeeded => streamFinished.succeed(()).unit
          case Resource.ExitCase.Canceled  => streamFinished.succeed(()).unit
          case Resource.ExitCase.Errored(throwable) =>
            streamFinished.fail(throwable).unit
        }
        .toUnicastPublisher
        .use { publisher =>
          Task(
            handler.onStream(
              publisher
            )
          )
        }
      _ <- streamFinished.await
    } yield null.asInstanceOf[Void]
  }
}

object Http4sClient {
  private case class ExtendedMethod(value: Method, canHaveBody: Boolean)

  private[http4s] case class Http4sClientBuilder(
      customization: BlazeClientBuilder[Task] => BlazeClientBuilder[Task] =
        identity
  )(implicit runtime: Runtime[Has[Clock]])
      extends SdkAsyncHttpClient.Builder[Http4sClientBuilder] {

    def withRuntime(runtime: Runtime[Has[Clock]]): Http4sClientBuilder =
      copy()(runtime)

    private def createClient(): Resource[Task, Client[Task]] = {
      customization(
        BlazeClientBuilder[Task].withExecutionContext(
          runtime.runtimeConfig.executor.asExecutionContext
        )
      ).resource
    }

    override def buildWithDefaults(
        serviceDefaults: AttributeMap
    ): SdkAsyncHttpClient = {
      val (client, closeFn) = runtime.unsafeRun(createClient().allocated)
      new Http4sClient(client, () => runtime.unsafeRun(closeFn))
    }

    def toManaged: ZManaged[Has[Clock], Throwable, Http4sClient] = {
      cats.effect.std
        .Dispatcher[Task]
        .allocated
        .toManaged
        .flatMap { case (d, r) =>
          implicit val dispatcher: Dispatcher[Task] = d
          ZManaged.acquireRelease(ZIO.unit)(r.ignore).flatMap { _ =>
            createClient().toManaged.map { client =>
              new Http4sClient(client, () => ())
            }
          }
        }
    }
  }

  private[http4s] def builder(): Http4sClientBuilder =
    Http4sClientBuilder()(Runtime.default)

  val default: ZLayer[Any, Throwable, Has[HttpClient]] = customized(identity)

  def customized(
      f: BlazeClientBuilder[Task] => BlazeClientBuilder[Task]
  ): ZLayer[Any, Throwable, Has[HttpClient]] =
    ZIO.runtime.toManaged_.flatMap { implicit runtime: Runtime[Any] =>
      Http4sClient
        .Http4sClientBuilder(f)
        .toManaged
        .map { c =>
          new HttpClient {
            override def clientFor(
                serviceCaps: ServiceHttpCapabilities
            ): Task[SdkAsyncHttpClient] = Task.succeed(c)
          }
        }
    }.toLayer

  def configured(
      sslContext: Option[SSLContext] = tryDefaultSslContext,
      maxConnectionsPerRequestKey: Option[RequestKey => Int] = None,
      asynchronousChannelGroup: Option[AsynchronousChannelGroup] = None,
      additionalSocketOptions: Seq[OptionValue[_]] = Seq.empty
  ): ZLayer[Has[
    _root_.io.github.vigoo.zioaws.http4s.BlazeClientConfig
  ], Throwable, Has[HttpClient]] =
    ZManaged
      .service[_root_.io.github.vigoo.zioaws.http4s.BlazeClientConfig]
      .flatMap { config =>
        ZManaged.runtime.flatMap { implicit runtime: Runtime[Any] =>
          Http4sClient
            .Http4sClientBuilder(
              _.withResponseHeaderTimeout(config.responseHeaderTimeout)
                .withIdleTimeout(config.idleTimeout)
                .withRequestTimeout(config.requestTimeout)
                .withConnectTimeout(config.connectTimeout)
                .withUserAgent(config.userAgent)
                .withMaxTotalConnections(config.maxTotalConnections)
                .withMaxWaitQueueLimit(config.maxWaitQueueLimit)
                .withCheckEndpointAuthentication(
                  config.checkEndpointIdentification
                )
                .withMaxResponseLineSize(config.maxResponseLineSize)
                .withMaxHeaderLength(config.maxHeaderLength)
                .withMaxChunkSize(config.maxChunkSize)
                .withChunkBufferMaxSize(config.chunkBufferMaxSize)
                .withParserMode(config.parserMode)
                .withBufferSize(config.bufferSize)
                .withChannelOptions(
                  ChannelOptions(
                    config.channelOptions.options ++ additionalSocketOptions
                  )
                )
                .withSslContextOption(sslContext)
                .withAsynchronousChannelGroupOption(asynchronousChannelGroup)
                .withMaxConnectionsPerRequestKey(
                  maxConnectionsPerRequestKey
                    .getOrElse(Function.const(config.maxWaitQueueLimit))
                )
            )
            .toManaged
            .map { c =>
              new HttpClient {
                override def clientFor(
                    serviceCaps: ServiceHttpCapabilities
                ): Task[SdkAsyncHttpClient] = Task.succeed(c)
              }
            }
        }
      }
      .toLayer

  private def tryDefaultSslContext: Option[SSLContext] =
    try {
      Some(SSLContext.getDefault)
    } catch {
      case NonFatal(_) => None
    }
}
