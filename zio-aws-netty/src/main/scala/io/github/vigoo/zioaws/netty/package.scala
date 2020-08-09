package io.github.vigoo.zioaws

import io.github.vigoo.zioaws.core.BuilderHelper
import io.github.vigoo.zioaws.core.httpclient.HttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.{NettyNioAsyncHttpClient, ProxyConfiguration}
import zio.duration.Duration
import zio.{ZIO, ZLayer, ZManaged}

package object netty {
  val builderHelper: BuilderHelper[SdkAsyncHttpClient] = BuilderHelper.apply
  import builderHelper._

  def client(connectionTimeout: Option[Duration] = None,
             maxConcurrency: Option[Int] = None,
             proxyConfiguration: Option[ProxyConfiguration] = None,
            ): ZLayer[Any, Throwable, HttpClient] = {
    ZManaged.fromAutoCloseable(
      ZIO(NettyNioAsyncHttpClient
        .builder()
        .optionallyWith(connectionTimeout)(_.connectionTimeout)
        .optionallyWith(maxConcurrency.map(_.asInstanceOf[Integer]))(_.maxConcurrency)
        .optionallyWith(proxyConfiguration)(_.proxyConfiguration)
        // TODO: expose additional configuration
        .build())).map { nettyClient =>
      new HttpClient.Service {
        override val client: SdkAsyncHttpClient = nettyClient
      }
    }.toLayer
  }
}
