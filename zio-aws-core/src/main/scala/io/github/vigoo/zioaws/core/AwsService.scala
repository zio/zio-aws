package io.github.vigoo.zioaws.core

import java.util.concurrent.CompletableFuture

import zio._

trait AwsServiceBase {
  def asyncRequestResponse[Request, Response](impl: Request => CompletableFuture[Response])(request: Request): IO[AwsError, Response] =
    ZIO.fromCompletionStage(impl(request)).mapError(AwsError.fromThrowable)
}
