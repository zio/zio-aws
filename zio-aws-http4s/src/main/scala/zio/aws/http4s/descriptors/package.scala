package zio.aws.http4s

import zio.aws.core.httpclient
import zio.aws.core.httpclient.descriptors.channelOptions
import org.http4s.ProductId
import org.http4s.blaze.channel.{ChannelOptions, OptionValue}
import org.http4s.blaze.client.ParserMode
import org.http4s.client.defaults
import org.http4s.headers.`User-Agent`
import zio.config._
import zio.config.ConfigDescriptor._

import java.net.SocketOption
import scala.concurrent.duration._

package object descriptors {
  val userAgent: ConfigDescriptor[`User-Agent`] =
    string.transformOrFail(
      s => `User-Agent`.parse(s).left.map(_.message),
      h => Right(h.toString())
    )
  val parserMode: ConfigDescriptor[ParserMode] =
    string.transformOrFail(
      {
        case "strict"  => Right(ParserMode.Strict)
        case "lenient" => Right(ParserMode.Lenient)
        case s: String =>
          Left(s"Invalid parser mode '$s'. Use 'strict' or 'lenient'")
      },
      {
        case ParserMode.Strict  => Right("strict")
        case ParserMode.Lenient => Right("lenient")
      }
    )

  val http4sChannelOptions: ConfigDescriptor[ChannelOptions] =
    channelOptions.transform(
      opts =>
        ChannelOptions(
          opts.options.map(opt => OptionValue(opt.key, opt.value))
        ),
      opts =>
        httpclient.ChannelOptions(
          opts.options.map(opt =>
            httpclient.OptionValue[Any](
              opt.key.asInstanceOf[SocketOption[Any]],
              opt.value
            )
          )
        )
    )

  val blazeClientConfig: ConfigDescriptor[BlazeClientConfig] = (
    duration("responseHeaderTimeout").default(
      Duration.Inf
    ) ?? "Timeout for receiving the header part of the response" zip
      duration("idleTimeout").default(
        1.minute
      ) ?? "Timeout for client connection staying idle" zip
      duration("requestTimeout").default(
        defaults.RequestTimeout
      ) ?? "Timeout for the whole request" zip
      duration("connectTimeout").default(
        defaults.ConnectTimeout
      ) ?? "Timeout for connecting to the server" zip
      nested("userAgent")(userAgent).default(
        `User-Agent`(ProductId("http4s-blaze", Some(BuildInfo.version)))
      ) ?? "User-Agent header sent by the client" zip
      int("maxTotalConnections").default(
        10
      ) ?? "Maximum number of parallel connections" zip
      int("maxWaitQueueLimit").default(
        256
      ) ?? "Maximum number of requests in queue" zip
      boolean("checkEndpointIdentification").default(
        true
      ) ?? "Check https identity" zip
      int("maxResponseLineSize").default(
        4096
      ) ?? "Maximum line length of headers in response" zip
      int("maxHeaderLength").default(
        40960
      ) ?? "Maximum total length of the response headers" zip
      int("maxChunkSize").default(Int.MaxValue) ?? "Maximum chunk size" zip
      int("chunkBufferMaxSize").default(
        1024 * 1024
      ) ?? "Maximum size of the chunk buffer" zip
      nested("parserMode")(parserMode)
        .default(ParserMode.Strict) ?? "Parser mode, strict or lenient" zip
      int("bufferSize").default(8192) ?? "Buffer size" zip
      nested("channelOptions")(http4sChannelOptions).default(
        ChannelOptions(
          Vector(
            OptionValue[java.lang.Boolean](
              java.net.StandardSocketOptions.TCP_NODELAY,
              true
            )
          )
        )
      ) ?? "Collection of socket options"
  ).to[BlazeClientConfig]
}
