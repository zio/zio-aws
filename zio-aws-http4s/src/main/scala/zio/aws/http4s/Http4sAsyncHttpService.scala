package zio.aws.http4s

import zio.aws.http4s.Http4sClient.Http4sClientBuilder
import software.amazon.awssdk.http.async.SdkAsyncHttpService

class Http4sAsyncHttpService extends SdkAsyncHttpService {
  override def createAsyncHttpClientFactory(): Http4sClientBuilder =
    Http4sClient.builder()
}
