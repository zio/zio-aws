package io.github.vigoo.zioaws.http4s

import java.util.concurrent.CompletableFuture

import cats.effect.{ExitCase, Resource}
import fs2._
import fs2.interop.reactivestreams._
import org.http4s._
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

class Http4sClient(client: Client[Task], closeFn: () => Unit)(implicit
    runtime: Runtime[Any]
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
      case SdkHttpMethod.GET     => ExtendedMethod(Method.GET, true)
      case SdkHttpMethod.POST    => ExtendedMethod(Method.POST, true)
      case SdkHttpMethod.PUT     => ExtendedMethod(Method.PUT, true)
      case SdkHttpMethod.DELETE  => ExtendedMethod(Method.DELETE, true)
      case SdkHttpMethod.HEAD    => ExtendedMethod(Method.HEAD, true)
      case SdkHttpMethod.PATCH   => ExtendedMethod(Method.PATCH, true)
      case SdkHttpMethod.OPTIONS => ExtendedMethod(Method.OPTIONS, true)
    }

  private def toHeaders(
      name: String,
      values: Iterable[String]
  ): List[Header.ToRaw] = {
    if (name == "Expect" && values.toSet == Set("100-continue")) {
      List.empty // skipping
    } else {
      values.map(value => Header.ToRaw.rawToRaw(Header.Raw(CIString(name), value))).toList
    }
  }

  private def toEntity(
      method: ExtendedMethod,
      publisher: SdkHttpContentPublisher
  ): EntityBody[Task] =
    if (method.canHaveBody) {
      publisher.toStream
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
          .flatMap { case (name, hdrs) => toHeaders(name, hdrs.asScala) } : _*
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
      _ <- Task(
        handler.onStream(
          response.body.chunks
            .map(_.toByteBuffer)
            .onFinalizeCase {
              case ExitCase.Completed => streamFinished.succeed(()).unit
              case ExitCase.Canceled  => streamFinished.succeed(()).unit
              case ExitCase.Error(throwable) =>
                streamFinished.fail(throwable).unit
            }
            .toUnicastPublisher
        )
      )
      _ <- streamFinished.await
    } yield null.asInstanceOf[Void]
  }
}

object Http4sClient {
  case class ExtendedMethod(value: Method, canHaveBody: Boolean)

  case class Http4sClientBuilder(
      customization: BlazeClientBuilder[Task] => BlazeClientBuilder[Task] =
        identity
  )(implicit runtime: Runtime[Any])
      extends SdkAsyncHttpClient.Builder[Http4sClientBuilder] {

    def withRuntime(runtime: Runtime[Any]): Http4sClientBuilder =
      copy()(runtime)

    private def createClient(): Resource[Task, Client[Task]] = {
      customization(
        BlazeClientBuilder[Task](runtime.platform.executor.asEC)
      ).resource
    }

    override def buildWithDefaults(
        serviceDefaults: AttributeMap
    ): SdkAsyncHttpClient = {
      val (client, closeFn) = runtime.unsafeRun(createClient().allocated)
      new Http4sClient(client, () => runtime.unsafeRun(closeFn))
    }

    def toManaged: ZManaged[Any, Throwable, Http4sClient] = {
      createClient().toManaged.map { client =>
        new Http4sClient(client, () => ())
      }
    }
  }

  def builder(): Http4sClientBuilder = Http4sClientBuilder()(Runtime.default)
}
