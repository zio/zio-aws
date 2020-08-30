package io.github.vigoo.zioaws

import io.github.vigoo.zioaws.core.BuilderHelper
import io.github.vigoo.zioaws.core.httpclient.HttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import zio.{ZIO, ZLayer, ZManaged}

package object netty {
  val builderHelper: BuilderHelper[SdkAsyncHttpClient] = BuilderHelper.apply

  def client(customization: NettyNioAsyncHttpClient.Builder => NettyNioAsyncHttpClient.Builder = identity): ZLayer[Any, Throwable, HttpClient] = {
    ZManaged.fromAutoCloseable(
      ZIO(customization(NettyNioAsyncHttpClient
        .builder())
        .build())).map { nettyClient =>
      new HttpClient.Service {
        override val client: SdkAsyncHttpClient = nettyClient
      }
    }.toLayer
  }
}
