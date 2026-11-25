package zio.aws.http4s

import org.http4s.blaze.channel.ChannelOptions
import org.http4s.blaze.client.ParserMode
import org.http4s.headers.`User-Agent`

import scala.concurrent.duration.Duration

/** Settings for `Http4sClient.configured`, which passes each field to the
  * matching `with...` method of the Blaze `BlazeClientBuilder`. Read it from
  * configuration with `descriptors.blazeClientConfig`, which also gives the
  * default values.
  *
  * @param responseHeaderTimeout
  *   how long to wait for the response header after the request is sent
  * @param idleTimeout
  *   how long a connection may stay idle
  * @param requestTimeout
  *   the time limit for the whole request
  * @param userAgent
  *   the `User-Agent` header that Blaze adds to a request that has none
  * @param maxTotalConnections
  *   the maximum number of open connections
  * @param maxWaitQueueLimit
  *   the maximum number of requests waiting for a connection;
  *   `Http4sClient.configured` also uses it as the connection limit per request
  *   key when it gets no `maxConnectionsPerRequestKey`
  * @param checkEndpointIdentification
  *   passed to `withCheckEndpointAuthentication`: whether Blaze checks that the
  *   certificate of an HTTPS server matches the host name of the request
  * @param maxResponseLineSize
  *   the maximum length of the response status line
  * @param maxHeaderLength
  *   the maximum total length of the response headers
  * @param maxChunkSize
  *   the maximum size of a chunk in a chunked response
  * @param chunkBufferMaxSize
  *   the maximum size of the buffer for a response body without a
  *   `Content-Length` header
  * @param parserMode
  *   whether the response parser is strict or lenient
  * @param bufferSize
  *   the internal buffer size of the Blaze client
  * @param channelOptions
  *   socket options set on each connection
  */
case class BlazeClientConfig(
    responseHeaderTimeout: Duration,
    idleTimeout: Duration,
    requestTimeout: Duration,
    connectTimeout: Duration,
    userAgent: `User-Agent`,
    maxTotalConnections: Int,
    maxWaitQueueLimit: Int,
    checkEndpointIdentification: Boolean,
    maxResponseLineSize: Int,
    maxHeaderLength: Int,
    maxChunkSize: Int,
    chunkBufferMaxSize: Int,
    parserMode: ParserMode,
    bufferSize: Int,
    channelOptions: ChannelOptions
)
