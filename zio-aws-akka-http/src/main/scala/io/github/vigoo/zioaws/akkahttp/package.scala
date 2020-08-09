package io.github.vigoo.zioaws

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ConnectionPoolSettings
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import io.github.vigoo.zioaws.core.BuilderHelper
import io.github.vigoo.zioaws.core.httpclient.HttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.{ZIO, ZLayer, ZManaged}

import scala.concurrent.ExecutionContext

package object akkahttp {
  val builderHelper: BuilderHelper[SdkAsyncHttpClient] = BuilderHelper.apply
  import builderHelper._

  def client(actorSystem: ActorSystem,
             connectionPoolSettings: Option[ConnectionPoolSettings] = None,
             executionContext: Option[ExecutionContext] = None
            ): ZLayer[Any, Throwable, HttpClient] = {
    ZManaged.fromAutoCloseable(
      ZIO(AkkaHttpClient
        .builder()
        .withActorSystem(actorSystem)
        .optionallyWith(connectionPoolSettings)(_.withConnectionPoolSettings)
        .optionallyWith(executionContext)(_.withExecutionContext)
        .build())).map { akkaClient =>
      new HttpClient.Service {
        override val client: SdkAsyncHttpClient = akkaClient
      }
    }.toLayer
  }
}
