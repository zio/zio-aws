package zio.aws.core

import zio.stream.ZStream
import zio.ZEnvironment

/** The result of an operation whose output is a stream: the operation's
  * `response` and the `output` stream. Operations with a byte-stream output,
  * such as S3's `getObject`, return it with `Item = Byte`. Some paginated
  * operations also return it, with `Item` the type of the page items; there
  * `response` is the first page's response and `output` streams the items of
  * every page.
  *
  * @see
  *   [[https://zio.dev/zio-aws/wrappers/ Wrappers]]
  */
case class StreamingOutputResult[R, Response, Item](
    response: Response,
    output: ZStream[R, AwsError, Item]
) {

  /** Returns a copy whose response is `f(response)`; the output stream is
    * unchanged.
    */
  def mapResponse[Response2](
      f: Response => Response2
  ): StreamingOutputResult[R, Response2, Item] =
    copy(response = f(response))

  /** Returns a copy whose output stream is `f(output)`; the response is
    * unchanged.
    */
  def mapOutput[I](
      f: ZStream[R, AwsError, Item] => ZStream[R, AwsError, I]
  ): StreamingOutputResult[R, Response, I] =
    copy(output = f(output))

  /** Returns a copy whose output stream gets its environment from `r`, so it
    * needs no environment.
    */
  def provideEnvironment(
      r: ZEnvironment[R]
  ): StreamingOutputResult[Any, Response, Item] =
    StreamingOutputResult(response, output.provideEnvironment(r))
}
