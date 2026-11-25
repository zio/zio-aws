package zio.aws.http4s

import zio.aws.http4s.Http4sClient.Http4sClientBuilder
import software.amazon.awssdk.http.async.SdkAsyncHttpService

/** An AWS SDK `SdkAsyncHttpService` whose factory builds an [[Http4sClient]]
  * that runs on `Runtime.default`. The `zio-aws-http4s` jar does not list it
  * under `META-INF/services`, so `java.util.ServiceLoader` does not find it;
  * the layers of `Http4sClient` do not need it.
  */
class Http4sAsyncHttpService extends SdkAsyncHttpService {
  override def createAsyncHttpClientFactory(): Http4sClientBuilder =
    Http4sClient.builder()
}
