package io.github.vigoo.zioaws.core

import zio.stream.ZStream

case class StreamingOutputResult[Response, Item](response: Response,
                                           output: ZStream[Any, AwsError, Item]) {
  def mapResponse[R](f: Response => R): StreamingOutputResult[R, Item] =
    copy(response = f(response))

  def mapOutput[I](f: ZStream[Any, AwsError, Item] => ZStream[Any, AwsError, I]): StreamingOutputResult[Response, I] =
    copy(output = f(output))
}
