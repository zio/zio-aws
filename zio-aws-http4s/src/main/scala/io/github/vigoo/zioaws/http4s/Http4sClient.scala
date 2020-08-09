package io.github.vigoo.zioaws.http4s

import java.util.concurrent.CompletableFuture

import cats.effect.{ExitCase, Resource}
import fs2._
import fs2.interop.reactivestreams._
import org.http4s.Method.{NoBody, PermitsBody}
import org.http4s._
import org.http4s.client._
import org.http4s.client.blaze._
import software.amazon.awssdk.http.async.{AsyncExecuteRequest, SdkAsyncHttpClient, SdkAsyncHttpResponseHandler, SdkHttpContentPublisher}
import software.amazon.awssdk.http.{SdkHttpMethod, SdkHttpRequest, SdkHttpResponse}
import software.amazon.awssdk.utils.AttributeMap
import zio._
import zio.interop.catz._

import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

class Http4sClient(client: Client[Task],
                   closeFn: () => Unit)
                  (implicit runtime: Runtime[Any])
  extends SdkAsyncHttpClient {

  import Http4sClient._

  override def execute(request: AsyncExecuteRequest): CompletableFuture[Void] = {
    runtime.unsafeRunToFuture(
      client.fetch(
        toHttp4sRequest(request.request(), request.requestContentPublisher()))(processResponse(_, request.responseHandler()))
    ).asJava.toCompletableFuture
  }

  override def close(): Unit = {
    closeFn()
  }

  private def captureCanHaveBody[M <: Method](m: M)(implicit capture: CaptureCanHaveBody[M]): ExtendedMethod =
    ExtendedMethod(m, capture.canHaveBody)

  private def toMethod(method: SdkHttpMethod): ExtendedMethod =
    method match {
      case SdkHttpMethod.GET => captureCanHaveBody(Method.GET)
      case SdkHttpMethod.POST => captureCanHaveBody(Method.POST)
      case SdkHttpMethod.PUT => captureCanHaveBody(Method.PUT)
      case SdkHttpMethod.DELETE => captureCanHaveBody(Method.DELETE)
      case SdkHttpMethod.HEAD => captureCanHaveBody(Method.HEAD)
      case SdkHttpMethod.PATCH => captureCanHaveBody(Method.PATCH)
      case SdkHttpMethod.OPTIONS => captureCanHaveBody(Method.OPTIONS)
    }

  private def toHeaders(name: String, values: Iterable[String]): List[Header] =
    values.map(Header(name, _)).toList

  private def toEntity(method: ExtendedMethod, publisher: SdkHttpContentPublisher): EntityBody[Task] =
    if (method.canHaveBody) {
      publisher
        .toStream()
        .map(fs2.Chunk.byteBuffer)
        .flatMap(Stream.chunk)
    } else {
      EmptyBody
    }

  private def toHttp4sRequest(request: SdkHttpRequest, contentPublisher: SdkHttpContentPublisher): Request[Task] = {
    val method = toMethod(request.method())
    Request(
      method = method.value,
      uri = Uri.unsafeFromString(request.getUri.toString),
      httpVersion = HttpVersion.`HTTP/1.1`,
      headers = Headers(request.headers()
        .asScala
        .toList
        .flatMap { case (name, hdrs) => toHeaders(name, hdrs.asScala) }),
      body = toEntity(method, contentPublisher)
    )
  }

  private def processResponse(response: Response[Task], handler: SdkAsyncHttpResponseHandler): Task[Void] = {
    for {
      _ <- Task(handler.onHeaders(
        SdkHttpResponse
          .builder()
          .headers(
            response
              .headers
              .toList
              .groupBy(_.name)
              .map { case (name, values) => (name.value, values.map(_.value).asJava) }
              .asJava)
          .statusCode(response.status.code)
          .statusText(response.status.reason)
          .build()))
      streamFinished <- Promise.make[Throwable, Unit]
      _ <- Task(handler.onStream(
        response
          .body
          .chunks
          .map(_.toByteBuffer)
          .onFinalizeCase {
            case ExitCase.Completed => streamFinished.succeed(()).unit
            case ExitCase.Canceled => streamFinished.succeed(()).unit
            case ExitCase.Error(throwable) => streamFinished.fail(throwable).unit
          }
          .toUnicastPublisher()))
      _ <- streamFinished.await
    } yield null.asInstanceOf[Void]
  }
}

object Http4sClient {
  case class ExtendedMethod(value: Method, canHaveBody: Boolean)

  trait CaptureCanHaveBody[M] {
    val canHaveBody: Boolean
  }

  object CaptureCanHaveBody {
    implicit def canHaveBody[M <: Method with PermitsBody]: CaptureCanHaveBody[M] = new CaptureCanHaveBody[M] {
      override val canHaveBody: Boolean = true
    }

    implicit def cannotHaveBody[M <: Method with NoBody]: CaptureCanHaveBody[M] = new CaptureCanHaveBody[M] {
      override val canHaveBody: Boolean = false
    }
  }

  case class Http4sClientBuilder()(implicit runtime: Runtime[Any])
    extends SdkAsyncHttpClient.Builder[Http4sClientBuilder] {

    def withRuntime(runtime: Runtime[Any]): Http4sClientBuilder =
      copy()(runtime)

    private def createClient(): Resource[Task, Client[Task]] = {
      BlazeClientBuilder[Task](runtime.platform.executor.asEC)
        // TODO: expose client settings
        .resource
    }

    override def buildWithDefaults(serviceDefaults: AttributeMap): SdkAsyncHttpClient = {
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
