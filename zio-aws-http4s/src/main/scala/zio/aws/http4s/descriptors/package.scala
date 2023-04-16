package zio.aws.http4s

import zio.aws.core.httpclient.descriptors.channelOptions
import org.http4s.{BuildInfo, ProductId}
import org.http4s.blaze.channel.{ChannelOptions, OptionValue}
import org.http4s.blaze.client.ParserMode
import org.http4s.client.defaults
import org.http4s.headers.`User-Agent`
import zio.Config

import scala.concurrent.duration._

package object descriptors {
  val userAgent: Config[`User-Agent`] =
    Config.string.mapOrFail(s =>
      `User-Agent`
        .parse(s)
        .left
        .map(r => Config.Error.InvalidData(message = r.message))
    )
  val parserMode: Config[ParserMode] =
    Config.string.mapOrFail(
      {
        case "strict"  => Right(ParserMode.Strict)
        case "lenient" => Right(ParserMode.Lenient)
        case s: String =>
          Left(
            Config.Error.InvalidData(message =
              s"Invalid parser mode '$s'. Use 'strict' or 'lenient'"
            )
          )
      }
    )

  val http4sChannelOptions: Config[ChannelOptions] =
    channelOptions.map(opts =>
      ChannelOptions(
        opts.options.map(opt => OptionValue(opt.key, opt.value))
      )
    )

  val blazeClientConfig: Config[BlazeClientConfig] = (
    Config
      .duration("responseHeaderTimeout")
      .map(d => Duration.fromNanos(d.toNanos))
      .withDefault(
        Duration.Inf
      ) ?? "Timeout for receiving the header part of the response" zip
      Config
        .duration("idleTimeout")
        .map(d => Duration.fromNanos(d.toNanos))
        .withDefault(
          1.minute
        ) ?? "Timeout for client connection staying idle" zip
      Config
        .duration("requestTimeout")
        .map(d => Duration.fromNanos(d.toNanos))
        .withDefault(
          defaults.RequestTimeout
        ) ?? "Timeout for the whole request" zip
      Config
        .duration("connectTimeout")
        .map(d => Duration.fromNanos(d.toNanos))
        .withDefault(
          defaults.ConnectTimeout
        ) ?? "Timeout for connecting to the server" zip
      userAgent
        .nested("userAgent")
        .withDefault(
          `User-Agent`(ProductId("http4s-blaze", Some(BuildInfo.version)))
        ) ?? "User-Agent header sent by the client" zip
      Config
        .int("maxTotalConnections")
        .withDefault(
          10
        ) ?? "Maximum number of parallel connections" zip
      Config
        .int("maxWaitQueueLimit")
        .withDefault(
          256
        ) ?? "Maximum number of requests in queue" zip
      Config
        .boolean("checkEndpointIdentification")
        .withDefault(
          true
        ) ?? "Check https identity" zip
      Config
        .int("maxResponseLineSize")
        .withDefault(
          4096
        ) ?? "Maximum line length of headers in response" zip
      Config
        .int("maxHeaderLength")
        .withDefault(
          40960
        ) ?? "Maximum total length of the response headers" zip
      Config
        .int("maxChunkSize")
        .withDefault(Int.MaxValue) ?? "Maximum chunk size" zip
      Config
        .int("chunkBufferMaxSize")
        .withDefault(
          1024 * 1024
        ) ?? "Maximum size of the chunk buffer" zip
      parserMode
        .nested("parserMode")
        .withDefault(ParserMode.Strict) ?? "Parser mode, strict or lenient" zip
      Config.int("bufferSize").withDefault(8192) ?? "Buffer size" zip
      http4sChannelOptions
        .nested("channelOptions")
        .withDefault(
          ChannelOptions(
            Vector(
              OptionValue[java.lang.Boolean](
                java.net.StandardSocketOptions.TCP_NODELAY,
                true
              )
            )
          )
        ) ?? "Collection of socket options"
  ).map {
    case (
          responseHeaderTimeout,
          idleTimeout,
          requestTimeout,
          connectTimeout,
          userAgent,
          maxTotalConnections,
          maxWaitQueueLimit,
          checkEndpointIdentification,
          maxResponseLineSize,
          maxHeaderLength,
          maxChunkSize,
          chunkBufferMaxSize,
          parserMode,
          bufferSize,
          channelOptions
        ) =>
      BlazeClientConfig(
        responseHeaderTimeout,
        idleTimeout,
        requestTimeout,
        connectTimeout,
        userAgent,
        maxTotalConnections,
        maxWaitQueueLimit,
        checkEndpointIdentification,
        maxResponseLineSize,
        maxHeaderLength,
        maxChunkSize,
        chunkBufferMaxSize,
        parserMode,
        bufferSize,
        channelOptions
      )
  }
}
