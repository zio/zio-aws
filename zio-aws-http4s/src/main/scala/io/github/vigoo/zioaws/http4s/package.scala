package io.github.vigoo.zioaws

import io.github.vigoo.zioaws.core.BuilderHelper
import io.github.vigoo.zioaws.core.httpclient.HttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.{Runtime, ZIO, ZLayer}

package object http4s {
  val builderHelper: BuilderHelper[SdkAsyncHttpClient] = BuilderHelper.apply
  import builderHelper._
  
  def client(): ZLayer[Any, Throwable, HttpClient] = {
    ZIO.runtime.toManaged_.flatMap { implicit runtime: Runtime[Any] =>
      Http4sClient.Http4sClientBuilder()
        .toManaged
        .map { c =>
          new HttpClient.Service {
            override val client: SdkAsyncHttpClient = c
          }
        }
    }.toLayer
  }
}
