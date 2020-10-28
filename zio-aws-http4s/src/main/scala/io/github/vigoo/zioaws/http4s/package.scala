package io.github.vigoo.zioaws

import java.net.SocketOption
import java.nio.channels.AsynchronousChannelGroup

import io.github.vigoo.zioaws.core.httpclient
import io.github.vigoo.zioaws.core.httpclient.HttpClient
import io.github.vigoo.zioaws.core.httpclient.descriptors.channelOptions
import javax.net.ssl.SSLContext
import org.http4s.blaze.channel.{ChannelOptions, OptionValue}
import org.http4s.client.blaze.{BlazeClientBuilder, ParserMode}
import org.http4s.client.{RequestKey, defaults}
import org.http4s.headers.{AgentProduct, `User-Agent`}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import zio.config.ConfigDescriptor._
import zio.config._
import zio.{Runtime, Task, ZIO, ZLayer}

import scala.concurrent.duration._
import scala.util.control.NonFatal

package object http4s {
  val default: ZLayer[Any, Throwable, HttpClient] = customized(identity)

  def customized(
      f: BlazeClientBuilder[Task] => BlazeClientBuilder[Task]
  ): ZLayer[Any, Throwable, HttpClient] =
    ZIO.runtime.toManaged_.flatMap { implicit runtime: Runtime[Any] =>
      Http4sClient
        .Http4sClientBuilder(f)
        .toManaged
        .map { c =>
          new HttpClient.Service {
            override val client: SdkAsyncHttpClient = c
          }
        }
    }.toLayer

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

  private def tryDefaultSslContext: Option[SSLContext] =
    try {
      Some(SSLContext.getDefault)
    } catch {
      case NonFatal(_) => None
    }

  def configured(
      sslContext: Option[SSLContext] = tryDefaultSslContext,
      maxConnectionsPerRequestKey: Option[RequestKey => Int] = None,
      asynchronousChannelGroup: Option[AsynchronousChannelGroup] = None,
      additionalSocketOptions: Seq[OptionValue[_]] = Seq.empty
  ): ZLayer[ZConfig[BlazeClientConfig], Throwable, HttpClient] =
    ZLayer.fromServiceManaged { config =>
      ZIO.runtime.toManaged_.flatMap { implicit runtime: Runtime[Any] =>
        Http4sClient
          .Http4sClientBuilder(
            _.withResponseHeaderTimeout(config.responseHeaderTimeout)
              .withIdleTimeout(config.idleTimeout)
              .withRequestTimeout(config.requestTimeout)
              .withConnectTimeout(config.connectTimeout)
              .withUserAgent(config.userAgent)
              .withMaxTotalConnections(config.maxTotalConnections)
              .withMaxWaitQueueLimit(config.maxWaitQueueLimit)
              .withCheckEndpointAuthentication(
                config.checkEndpointIdentification
              )
              .withMaxResponseLineSize(config.maxResponseLineSize)
              .withMaxHeaderLength(config.maxHeaderLength)
              .withMaxChunkSize(config.maxChunkSize)
              .withChunkBufferMaxSize(config.chunkBufferMaxSize)
              .withParserMode(config.parserMode)
              .withBufferSize(config.bufferSize)
              .withChannelOptions(
                ChannelOptions(
                  config.channelOptions.options ++ additionalSocketOptions
                )
              )
              .withSslContextOption(sslContext)
              .withAsynchronousChannelGroupOption(asynchronousChannelGroup)
              .withMaxConnectionsPerRequestKey(
                maxConnectionsPerRequestKey
                  .getOrElse(Function.const(config.maxWaitQueueLimit))
              )
          )
          .toManaged
          .map { c =>
            new HttpClient.Service {
              override val client: SdkAsyncHttpClient = c
            }
          }
      }
    }

  object descriptors {
    val userAgent: ConfigDescriptor[`User-Agent`] =
      string.xmapEither(
        s => `User-Agent`.parse(s).left.map(_.message),
        h => Right(h.toString())
      )
    val parserMode: ConfigDescriptor[ParserMode] =
      string.xmapEither(
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
      ) ?? "Timeout for receiving the header part of the response" |@|
        duration("idleTimeout").default(
          1.minute
        ) ?? "Timeout for client connection staying idle" |@|
        duration("requestTimeout").default(
          defaults.RequestTimeout
        ) ?? "Timeout for the whole request" |@|
        duration("connectTimeout").default(
          defaults.ConnectTimeout
        ) ?? "Timeout for connecting to the server" |@|
        nested("userAgent")(userAgent).default(
          `User-Agent`(AgentProduct("http4s-blaze", Some(BuildInfo.version)))
        ) ?? "User-Agent header sent by the client" |@|
        int("maxTotalConnections").default(
          10
        ) ?? "Maximum number of parallel connections" |@|
        int("maxWaitQueueLimit").default(
          256
        ) ?? "Maximum number of requests in queue" |@|
        boolean("checkEndpointIdentification").default(
          true
        ) ?? "Check https identity" |@|
        int("maxResponseLineSize").default(
          4096
        ) ?? "Maximum line length of headers in response" |@|
        int("maxHeaderLength").default(
          40960
        ) ?? "Maximum total length of the response headers" |@|
        int("maxChunkSize").default(Int.MaxValue) ?? "Maximum chunk size" |@|
        int("chunkBufferMaxSize").default(
          1024 * 1024
        ) ?? "Maximum size of the chunk buffer" |@|
        nested("parserMode")(parserMode)
          .default(ParserMode.Strict) ?? "Parser mode, strict or lenient" |@|
        int("bufferSize").default(8192) ?? "Buffer size" |@|
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
    )(BlazeClientConfig.apply, BlazeClientConfig.unapply)
  }

}
