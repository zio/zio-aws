package io.github.vigoo.zioaws.http4s

import io.github.vigoo.zioaws.http4s.Http4sClient.Http4sClientBuilder
import software.amazon.awssdk.http.async.SdkAsyncHttpService

class Http4sAsyncHttpService extends SdkAsyncHttpService {
  override def createAsyncHttpClientFactory(): Http4sClientBuilder = Http4sClient.builder()
}
