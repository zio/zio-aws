package zio.aws.akkahttp

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ConnectionPoolSettings
import com.github.matsluni.akkahttpspi.{AkkaHttpClient => SPI}
import zio.aws.core.BuilderHelper
import zio.aws.core.httpclient.{HttpClient, ServiceHttpCapabilities}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.{Scope, Task, ZIO, ZLayer}

import scala.concurrent.ExecutionContext

/**
 * Builds a `zio.aws.core.httpclient.HttpClient` layer backed by Akka HTTP, through the aws-spi-akka-http library.
 *
 * @see [[https://zio.dev/zio-aws/http/ HTTP Clients]]
 */
object AkkaHttpClient {
  /** Adds `optionallyWith` to the aws-spi-akka-http builder; `client` uses it to apply its optional settings. */
  val builderHelper: BuilderHelper[SdkAsyncHttpClient] = BuilderHelper.apply

  import builderHelper._

  /**
   * Builds a layer with an Akka HTTP client that runs on the `ActorSystem` in the environment, and returns this client for every service. When the layer's scope ends it closes the client and leaves the `ActorSystem` running, so whoever created the system terminates it.
   *
   * {{{
   * import zio._
   * import akka.actor.ActorSystem
   * import zio.aws.core.httpclient.HttpClient
   *
   * val actorSystem: ZLayer[Any, Throwable, ActorSystem] =
   *   ZLayer.scoped(
   *     ZIO.acquireRelease(ZIO.attempt(ActorSystem("aws")))(system =>
   *       ZIO.fromFuture(_ => system.terminate()).orDie
   *     )
   *   )
   *
   * val httpClient: ZLayer[Any, Throwable, HttpClient] =
   *   actorSystem >>> AkkaHttpClient.client()
   * }}}
   *
   * @param connectionPoolSettings the Akka HTTP connection pool settings; `None` reads them from the configuration of the actor system
   * @param executionContext the execution context of the client; `None` uses the dispatcher of the actor system
   * @see [[https://zio.dev/zio-aws/http/ HTTP Clients]]
   */
  def client(
      connectionPoolSettings: Option[ConnectionPoolSettings] = None,
      executionContext: Option[ExecutionContext] = None
  ): ZLayer[ActorSystem, Throwable, HttpClient] =
    ZLayer.scoped {
      for {
        actorSystem <- ZIO.service[ActorSystem]
        akkaClient <- ZIO
          .fromAutoCloseable(
            ZIO.attempt(
              SPI
                .builder()
                .withActorSystem(actorSystem)
                .optionallyWith(connectionPoolSettings)(
                  _.withConnectionPoolSettings
                )
                .optionallyWith(executionContext)(_.withExecutionContext)
                .build()
            )
          )
      } yield new HttpClient {
        override def clientFor(
            serviceCaps: ServiceHttpCapabilities
        ): Task[SdkAsyncHttpClient] = ZIO.succeed(akkaClient)
      }
    }
}
