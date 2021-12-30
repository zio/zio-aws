package zio.aws.http4s

import org.http4s.blaze.channel.ChannelOptions
import org.http4s.blaze.client.ParserMode
import org.http4s.headers.`User-Agent`

import scala.concurrent.duration.Duration

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
