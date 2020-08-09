package io.github.vigoo.zioaws.core

import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.{Has, UIO, URIO, ZIO, ZLayer}

package object httpclient {
  type HttpClient = Has[HttpClient.Service]

  object HttpClient {
    trait Service {
      val client: SdkAsyncHttpClient
    }
  }

  def client(): URIO[HttpClient.Service, SdkAsyncHttpClient] = ZIO.access(_.client)
}
