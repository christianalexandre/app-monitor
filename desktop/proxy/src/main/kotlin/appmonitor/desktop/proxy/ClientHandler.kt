package appmonitor.desktop.proxy

import appmonitor.v1.Envelope
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.ssl.OptionalSslHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.util.ReferenceCountUtil
import java.net.URI
import java.net.URISyntaxException

/** Where one client connection's requests go: the `CONNECT` target in a tunnel, else each absolute URL's origin. */
internal data class Target(
    val host: String,
    val port: Int,
    val tls: Boolean,
) {
    val origin: String
        get() {
            val name = if (':' in host) "[$host]" else host
            val default = if (tls) 443 else 80
            return (if (tls) "https://" else "http://") + name + (if (port == default) "" else ":$port")
        }
}

/**
 * One client connection: a `CONNECT` becomes a tunnel whose TLS, when there is some, ends here with a leaf from [ca];
 * each request is relayed to its [Target] over one upstream connection at a time, and captured. Event loop only.
 */
internal class ClientHandler(
    private val ca: ProxyCa,
    private val upstreamTls: SslContext,
    private val upstreamHttp2Tls: SslContext,
    private val record: (Envelope) -> Unit,
    private val clock: () -> Long,
) : ChannelInboundHandlerAdapter() {
    private lateinit var client: ChannelHandlerContext
    private var tunnel: Target? = null
    private var connectCapture: Capture? = null
    private var skipContent = false
    private var upstream: Channel? = null
    private var upstreamTarget: Target? = null
    private var connecting = false
    private val pending = ArrayDeque<Any>()

    // A request read while one is in flight waits here: the relay is one exchange at a time.
    private val queued = ArrayDeque<Any>()
    private var exchange: Exchange? = null
    private var raw = false

    private class Exchange(
        val capture: Capture,
        val keepAlive: Boolean,
    ) {
        var responded = false
        var informational = false
        var status = 0
        var response: HttpResponse? = null
        val body = BodyCapture()
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        client = ctx
    }

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        if (raw) return relay(msg, upstream)
        if (queued.isNotEmpty() || (exchange != null && msg is HttpRequest)) {
            queued.addLast(msg)
            return
        }
        dispatch(msg)
    }

    private fun dispatch(msg: Any) {
        when (msg) {
            is HttpRequest -> request(msg)
            is HttpContent -> content(msg)
            else -> ReferenceCountUtil.release(msg)
        }
    }

    private fun request(msg: HttpRequest) {
        if (msg.decoderResult().isFailure) {
            ReferenceCountUtil.release(msg)
            return reply(HttpResponseStatus.BAD_REQUEST, "App Monitor proxy: unreadable request", close = true)
        }
        if (msg.method() == HttpMethod.CONNECT && tunnel == null) return connect(msg)
        val target = tunnel ?: absoluteTarget(msg.uri())
        if (target == null) {
            ReferenceCountUtil.release(msg)
            skipContent = true
            return reply(HttpResponseStatus.BAD_REQUEST, "App Monitor proxy: send an absolute URL or CONNECT", close = true)
        }
        val path = if (tunnel != null) msg.uri() else originForm(msg.uri())
        val capture = Capture(msg.method().name(), target.origin + path, headersOf(msg.headers()), record, clock)
        exchange = Exchange(capture, HttpUtil.isKeepAlive(msg))
        msg.uri = path
        msg.headers().remove("Proxy-Connection")
        if (!msg.headers().contains(HttpHeaderNames.HOST)) msg.headers().set(HttpHeaderNames.HOST, target.origin.substringAfter("://"))
        val open = upstream
        if (open != null && open.isActive && upstreamTarget == target) {
            forward(msg)
        } else {
            open?.close()
            pending.addLast(msg)
            openUpstream(target)
        }
    }

    private fun content(msg: HttpContent) {
        val current = exchange
        if (skipContent || current == null) {
            if (msg is LastHttpContent) skipContent = false
            ReferenceCountUtil.release(msg)
            return
        }
        current.capture.requestContent(msg.content())
        if (msg is LastHttpContent) {
            current.capture.requestDone()
            client.channel().config().isAutoRead = false
        }
        forward(msg)
    }

    // ADR-0101 E.1: every host is intercepted; a client refusing the leaf is a failed CONNECT exchange.
    private fun connect(msg: HttpRequest) {
        val target = connectTarget(msg.uri())
        skipContent = true
        if (target == null) {
            ReferenceCountUtil.release(msg)
            return reply(HttpResponseStatus.BAD_REQUEST, "App Monitor proxy: CONNECT needs host:port", close = true)
        }
        tunnel = target
        connectCapture = Capture("CONNECT", target.copy(tls = true).origin, headersOf(msg.headers()), record, clock)
        ReferenceCountUtil.release(msg)
        val established = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus(200, "Connection Established"))
        client.writeAndFlush(established)
        val pipeline = client.pipeline()
        pipeline.remove(ProxyServer.CODEC)
        pipeline.addBefore(client.name(), "tls", OptionalSslHandler(ca.serverContext(target.host)))
        pipeline.addBefore(client.name(), "preface", PrefaceDetector(::http2))
        pipeline.addBefore(client.name(), ProxyServer.CODEC, HttpServerCodec())
    }

    // ADR-0106: a tunnel that opens with the HTTP/2 preface is relayed stream by stream from here on.
    private fun http2() {
        val target = tunnel ?: return
        connectCapture = null
        client.pipeline().remove(ProxyServer.CODEC)
        Http2Session(target, upstreamHttp2Tls, record, clock).install(client.pipeline(), client.name())
    }

    override fun userEventTriggered(
        ctx: ChannelHandlerContext,
        evt: Any,
    ) {
        if (evt is SslHandshakeCompletionEvent) {
            val capture = connectCapture
            connectCapture = null
            if (evt.isSuccess) {
                tunnel = tunnel?.copy(tls = true)
            } else {
                capture?.failed("TLS handshake with the client failed: ${evt.cause().message ?: evt.cause()}")
                ctx.close()
            }
        }
        super.userEventTriggered(ctx, evt)
    }

    private fun openUpstream(target: Target) {
        connecting = true
        upstreamTarget = target
        val handler = UpstreamHandler()
        val future =
            Bootstrap()
                .group(client.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            if (target.tls) ch.pipeline().addLast("tls", upstreamTls.newHandler(ch.alloc(), target.host, target.port))
                            ch.pipeline().addLast(ProxyServer.CODEC, HttpClientCodec())
                            ch.pipeline().addLast(handler)
                        }
                    },
                ).connect(target.host, target.port)
        upstream = future.channel()
        future.addListener(
            ChannelFutureListener { done ->
                connecting = false
                if (done.isSuccess) {
                    while (pending.isNotEmpty()) forward(pending.removeFirst())
                } else {
                    releaseAll(pending)
                    fail("Could not connect to ${target.host}:${target.port}: ${done.cause().message ?: done.cause()}")
                }
            },
        )
    }

    private fun forward(msg: Any) {
        if (connecting) {
            pending.addLast(msg)
            return
        }
        val open = upstream
        if (open == null || !open.isActive) {
            ReferenceCountUtil.release(msg)
            return
        }
        open.writeAndFlush(msg).addListener(
            ChannelFutureListener { done ->
                if (!done.isSuccess) fail("Could not write to ${upstreamTarget?.host}: ${done.cause().message}")
            },
        )
    }

    /** Ends the exchange in flight as failed: a `502` when the client has no response yet, else a closed connection. */
    private fun fail(error: String) {
        val current = exchange ?: return
        if (current.capture.ended) return
        current.capture.failed(error)
        upstream?.close()
        upstream = null
        if (current.responded) {
            client.close()
        } else {
            reply(HttpResponseStatus.BAD_GATEWAY, "App Monitor proxy: $error", close = true)
        }
    }

    private fun reply(
        status: HttpResponseStatus,
        text: String,
        close: Boolean,
    ) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(text, Charsets.UTF_8))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
        HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
        if (close) response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        val written = client.writeAndFlush(response)
        if (close) written.addListener(ChannelFutureListener.CLOSE)
    }

    private fun responseEnded(current: Exchange) {
        current.capture.responded(current.status, headersOf(current.response!!.headers()), current.body)
        exchange = null
        if (!current.keepAlive || !HttpUtil.isKeepAlive(current.response!!)) {
            client.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
            return
        }
        client.channel().config().isAutoRead = true
        while (queued.isNotEmpty() && !(exchange != null && queued.first() is HttpRequest)) dispatch(queued.removeFirst())
        client.read()
    }

    // A 101 hands both sides over to raw bytes; what crosses after it is not captured (DEBT-0077).
    private fun upgrade(upstreamContext: ChannelHandlerContext) {
        raw = true
        client.pipeline().remove(ProxyServer.CODEC)
        upstreamContext.pipeline().remove(ProxyServer.CODEC)
        client.channel().config().isAutoRead = true
        while (queued.isNotEmpty()) relay(queued.removeFirst(), upstream)
        client.read()
    }

    private fun relay(
        msg: Any,
        to: Channel?,
    ) {
        if (msg !is ByteBuf || to == null || !to.isActive) {
            ReferenceCountUtil.release(msg)
            return
        }
        to.writeAndFlush(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        exchange?.capture?.failed("The client closed the connection")
        exchange = null
        upstream?.close()
        releaseAll(pending)
        releaseAll(queued)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        ctx.close()
    }

    private inner class UpstreamHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            if (raw) return relay(msg, client.channel())
            val current = exchange
            if (current == null || current.capture.ended) {
                ReferenceCountUtil.release(msg)
                return
            }
            if (msg is HttpResponse) {
                val status = msg.status().code()
                current.informational = status in 100..199 && status != 101
                if (!current.informational) {
                    current.responded = true
                    current.status = status
                    current.response = msg
                }
            }
            if (msg is HttpContent && !current.informational) current.body.add(msg.content())
            val last = msg is LastHttpContent
            client.writeAndFlush(msg)
            when {
                current.informational -> {
                    if (last) current.informational = false
                }

                last && current.status == 101 -> {
                    current.capture.responded(101, headersOf(current.response!!.headers()), current.body)
                    exchange = null
                    upgrade(ctx)
                }

                last -> {
                    responseEnded(current)
                }
            }
        }

        override fun userEventTriggered(
            ctx: ChannelHandlerContext,
            evt: Any,
        ) {
            if (evt is SslHandshakeCompletionEvent && !evt.isSuccess) {
                val target = upstreamTarget
                fail("TLS handshake with ${target?.host}:${target?.port} failed: ${evt.cause().message ?: evt.cause()}")
            }
            super.userEventTriggered(ctx, evt)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            if (raw) {
                client.close()
                return
            }
            if (upstream != ctx.channel()) return
            upstream = null
            if (exchange != null) fail("${upstreamTarget?.host} closed the connection before the response ended")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun exceptionCaught(
            ctx: ChannelHandlerContext,
            cause: Throwable,
        ) {
            fail(cause.message ?: cause.toString())
            ctx.close()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000

        private fun releaseAll(messages: ArrayDeque<Any>) {
            while (messages.isNotEmpty()) ReferenceCountUtil.release(messages.removeFirst())
        }

        private fun connectTarget(authority: String): Target? =
            try {
                val uri = URI("//$authority")
                if (uri.host == null || uri.port == -1) null else Target(uri.host.removeSurrounding("[", "]"), uri.port, tls = false)
            } catch (_: URISyntaxException) {
                null
            }

        private fun absoluteTarget(raw: String): Target? =
            try {
                val uri = URI(raw)
                val tls =
                    when (uri.scheme?.lowercase()) {
                        "http" -> false
                        "https" -> true
                        else -> return null
                    }
                val host = uri.host ?: return null
                Target(host.removeSurrounding("[", "]"), if (uri.port == -1) (if (tls) 443 else 80) else uri.port, tls)
            } catch (_: URISyntaxException) {
                null
            }

        private fun originForm(raw: String): String {
            val uri = URI(raw)
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            return if (uri.rawQuery == null) path else "$path?${uri.rawQuery}"
        }
    }
}
