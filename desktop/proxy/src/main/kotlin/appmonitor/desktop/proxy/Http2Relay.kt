package appmonitor.desktop.proxy

import appmonitor.v1.Envelope
import appmonitor.v1.Header
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame
import io.netty.handler.codec.http2.Http2CodecUtil
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2ResetFrame
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.codec.http2.Http2StreamChannel
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.util.ReferenceCountUtil

/** Calls [onHttp2] when a tunnel starts with the HTTP/2 connection preface, then hands every byte on unchanged. */
internal class PrefaceDetector(
    private val onHttp2: () -> Unit,
) : ByteToMessageDecoder() {
    override fun decode(
        ctx: ChannelHandlerContext,
        input: ByteBuf,
        out: MutableList<Any>,
    ) {
        val seen = minOf(input.readableBytes(), PREFACE.readableBytes())
        val matches = (0 until seen).all { input.getByte(input.readerIndex() + it) == PREFACE.getByte(it) }
        if (matches && seen < PREFACE.readableBytes()) return
        if (matches) onHttp2()
        ctx.pipeline().remove(this)
    }

    private companion object {
        val PREFACE: ByteBuf = Http2CodecUtil.connectionPrefaceBuf()
    }
}

/**
 * The HTTP/2 side of one tunnel to [target]: each client stream is relayed on one upstream HTTP/2 connection, opened on
 * first use over TLS with ALPN `h2` when the tunnel had TLS, else with prior knowledge. Event loop only.
 */
internal class Http2Session(
    private val target: Target,
    private val upstreamTls: SslContext,
    private val record: (Envelope) -> Unit,
    private val clock: () -> Long,
) : ChannelInboundHandlerAdapter() {
    private lateinit var client: Channel
    private var upstream: Channel? = null
    private var ready = false
    private val waiting = ArrayDeque<(Result<Channel>) -> Unit>()

    /** The client side's codec, stream handler and this session, in place of the HTTP/1.1 ones. */
    fun install(
        pipeline: ChannelPipeline,
        before: String,
    ) {
        pipeline.addBefore(before, "h2", Http2FrameCodecBuilder.forServer().build())
        pipeline.addBefore(
            before,
            "h2-streams",
            Http2MultiplexHandler(
                object : ChannelInitializer<Http2StreamChannel>() {
                    override fun initChannel(ch: Http2StreamChannel) {
                        ch.pipeline().addLast(StreamRelay())
                    }
                },
            ),
        )
        pipeline.replace(before, "h2-session", this)
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        client = ctx.channel()
    }

    // Connection-level frames (settings, ping, goaway) end here.
    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        ReferenceCountUtil.release(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        upstream?.close()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        ctx.close()
    }

    private fun withUpstream(then: (Result<Channel>) -> Unit) {
        val open = upstream
        if (open != null && open.isActive && ready) return then(Result.success(open))
        waiting.addLast(then)
        if (open != null && open.isActive) return
        connect()
    }

    private fun connect() {
        ready = false
        val future =
            Bootstrap()
                .group(client.eventLoop())
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            if (target.tls) ch.pipeline().addLast("tls", upstreamTls.newHandler(ch.alloc(), target.host, target.port))
                            ch.pipeline().addLast(
                                Http2FrameCodecBuilder
                                    .forClient()
                                    .initialSettings(Http2Settings.defaultSettings().pushEnabled(false))
                                    .build(),
                            )
                            ch.pipeline().addLast(Http2MultiplexHandler(ChannelInboundHandlerAdapter()))
                            ch.pipeline().addLast(UpstreamConnection())
                        }
                    },
                ).connect(target.host, target.port)
        upstream = future.channel()
        future.addListener(
            ChannelFutureListener { done ->
                when {
                    !done.isSuccess -> {
                        failWaiting(
                            "Could not connect to ${target.host}:${target.port}: ${done.cause().message ?: done.cause()}",
                        )
                    }

                    !target.tls -> {
                        becomeReady(done.channel())
                    }
                }
            },
        )
    }

    private fun becomeReady(channel: Channel) {
        ready = true
        while (waiting.isNotEmpty()) waiting.removeFirst()(Result.success(channel))
    }

    private fun failWaiting(error: String) {
        upstream?.close()
        upstream = null
        while (waiting.isNotEmpty()) waiting.removeFirst()(Result.failure(IllegalStateException(error)))
    }

    private inner class UpstreamConnection : ChannelInboundHandlerAdapter() {
        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            ReferenceCountUtil.release(msg)
        }

        override fun userEventTriggered(
            ctx: ChannelHandlerContext,
            evt: Any,
        ) {
            if (evt is SslHandshakeCompletionEvent) {
                val protocol = ctx.pipeline().get(SslHandler::class.java)?.applicationProtocol()
                when {
                    !evt.isSuccess -> {
                        failWaiting(
                            "TLS handshake with ${target.host}:${target.port} failed: ${evt.cause().message ?: evt.cause()}",
                        )
                    }

                    protocol != ApplicationProtocolNames.HTTP_2 -> {
                        failWaiting("${target.host}:${target.port} does not offer HTTP/2")
                    }

                    else -> {
                        becomeReady(ctx.channel())
                    }
                }
            }
            super.userEventTriggered(ctx, evt)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            if (upstream == ctx.channel()) {
                upstream = null
                failWaiting("${target.host} closed the connection")
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun exceptionCaught(
            ctx: ChannelHandlerContext,
            cause: Throwable,
        ) {
            ctx.close()
        }
    }

    /** One client stream and its upstream twin: frames relayed as they come, the exchange captured (SDD 11). */
    private inner class StreamRelay : ChannelInboundHandlerAdapter() {
        private lateinit var stream: ChannelHandlerContext
        private var capture: Capture? = null
        private var grpc = false
        private var twin: Channel? = null
        private val pending = ArrayDeque<Any>()
        private val responseBody = BodyCapture()
        private var requestMessages: GrpcMessages? = null
        private var responseMessages: GrpcMessages? = null
        private var responded = false
        private var status = 0
        private val responseHeaders = mutableListOf<Header>()

        override fun handlerAdded(ctx: ChannelHandlerContext) {
            stream = ctx
        }

        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            when (msg) {
                is Http2HeadersFrame -> {
                    if (capture == null) start(msg.headers())
                    if (msg.isEndStream) requestEnded()
                    toUpstream(DefaultHttp2HeadersFrame(msg.headers(), msg.isEndStream))
                }

                is Http2DataFrame -> {
                    val messages = requestMessages
                    if (messages != null) messages.add(msg.content()) else capture?.requestContent(msg.content())
                    if (msg.isEndStream) requestEnded()
                    toUpstream(DefaultHttp2DataFrame(msg.content(), msg.isEndStream))
                }

                is Http2ResetFrame -> {
                    capture?.failed("The client reset the stream: ${Http2Error.valueOf(msg.errorCode()) ?: msg.errorCode()}")
                    toUpstream(DefaultHttp2ResetFrame(msg.errorCode()))
                }

                else -> {
                    ReferenceCountUtil.release(msg)
                }
            }
        }

        private fun start(headers: Http2Headers) {
            val path = headers.path()?.toString() ?: "/"
            val type = headers.get("content-type")?.toString().orEmpty()
            grpc = type.startsWith("application/grpc") && !type.startsWith("application/grpc-web")
            val started = Capture(headers.method()?.toString() ?: "GET", target.origin + path, stored(headers), record, clock)
            capture = started
            if (grpc) {
                started.requestDone()
                requestMessages = GrpcMessages { started.frame(sent = true, message = it) }
                responseMessages = GrpcMessages { started.frame(sent = false, message = it) }
            }
            withUpstream { result ->
                result.fold(
                    onSuccess = { parent -> openTwin(parent) },
                    onFailure = { fail(it.message ?: it.toString(), reply = true) },
                )
            }
        }

        private fun requestEnded() {
            capture?.requestDone()
        }

        private fun openTwin(parent: Channel) {
            if (!stream.channel().isActive) return releaseAll()
            val opening = Http2StreamChannelBootstrap(parent).handler(Twin()).open()
            opening.addListener {
                if (opening.isSuccess) {
                    twin = opening.now
                    while (pending.isNotEmpty()) toUpstream(pending.removeFirst())
                } else {
                    fail("Could not open a stream to ${target.host}: ${opening.cause().message ?: opening.cause()}", reply = true)
                }
            }
        }

        private fun toUpstream(frame: Any) {
            val open = twin
            when {
                open == null && capture?.ended != true -> {
                    pending.addLast(frame)
                }

                open == null || !open.isActive -> {
                    ReferenceCountUtil.release(frame)
                }

                else -> {
                    open.writeAndFlush(frame).addListener(
                        ChannelFutureListener { done ->
                            if (!done.isSuccess) fail("Could not write to ${target.host}: ${done.cause().message}", reply = true)
                        },
                    )
                }
            }
        }

        /** Ends the exchange as failed: a `502` when the client has no response yet and [reply], else a reset. */
        private fun fail(
            error: String,
            reply: Boolean,
        ) {
            val current = capture ?: return
            if (current.ended) return
            current.failed(error)
            releaseAll()
            twin?.close()
            if (!stream.channel().isActive) return
            if (reply && !responded) {
                val headers = DefaultHttp2Headers().status("502").set("content-type", "text/plain; charset=utf-8")
                stream.write(DefaultHttp2HeadersFrame(headers))
                stream.writeAndFlush(DefaultHttp2DataFrame(Unpooled.copiedBuffer("App Monitor proxy: $error", Charsets.UTF_8), true))
            } else {
                stream.writeAndFlush(DefaultHttp2ResetFrame(Http2Error.CANCEL))
            }
        }

        private fun releaseAll() {
            while (pending.isNotEmpty()) ReferenceCountUtil.release(pending.removeFirst())
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            capture?.failed("The client closed the stream")
            releaseAll()
            twin?.close()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun exceptionCaught(
            ctx: ChannelHandlerContext,
            cause: Throwable,
        ) {
            fail(cause.message ?: cause.toString(), reply = false)
            ctx.close()
        }

        private fun responseEnded() {
            capture?.responded(status, responseHeaders.toList(), if (grpc) BodyCapture() else responseBody)
        }

        /** The upstream stream: its frames go back to the client stream. */
        private inner class Twin : ChannelInboundHandlerAdapter() {
            override fun channelRead(
                ctx: ChannelHandlerContext,
                msg: Any,
            ) {
                if (!stream.channel().isActive) {
                    ReferenceCountUtil.release(msg)
                    return
                }
                when (msg) {
                    is Http2HeadersFrame -> {
                        val code =
                            msg
                                .headers()
                                .status()
                                ?.toString()
                                ?.toIntOrNull()
                        // A 1xx is passed on and not stored; trailers add to the response's headers.
                        if (!responded && code != null && code in 100..199) {
                            stream.writeAndFlush(DefaultHttp2HeadersFrame(msg.headers(), msg.isEndStream))
                            return
                        }
                        if (!responded) {
                            responded = true
                            status = code ?: 0
                        }
                        responseHeaders += stored(msg.headers())
                        stream.writeAndFlush(DefaultHttp2HeadersFrame(msg.headers(), msg.isEndStream))
                        if (msg.isEndStream) responseEnded()
                    }

                    is Http2DataFrame -> {
                        val messages = responseMessages
                        if (messages != null) messages.add(msg.content()) else responseBody.add(msg.content())
                        val end = msg.isEndStream
                        stream.writeAndFlush(DefaultHttp2DataFrame(msg.content(), end))
                        if (end) responseEnded()
                    }

                    is Http2ResetFrame -> {
                        fail("${target.host} reset the stream: ${Http2Error.valueOf(msg.errorCode()) ?: msg.errorCode()}", reply = false)
                    }

                    else -> {
                        ReferenceCountUtil.release(msg)
                    }
                }
            }

            override fun channelInactive(ctx: ChannelHandlerContext) {
                fail("${target.host} closed the stream before the response ended", reply = true)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun exceptionCaught(
                ctx: ChannelHandlerContext,
                cause: Throwable,
            ) {
                fail(cause.message ?: cause.toString(), reply = true)
                ctx.close()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        /** Headers as stored: the pseudo-headers are already the method, URL and status. */
        fun stored(headers: Http2Headers): List<Header> = headersOf(headers).filterNot { it.name.startsWith(":") }
    }
}

/**
 * Splits a gRPC body into its length-prefixed messages (flag byte, 4-byte length, bytes), each handed to [onMessage]
 * as [BodyCapture] keeps it. A compressed message stays as sent.
 */
internal class GrpcMessages(
    private val onMessage: (BodyCapture) -> Unit,
) {
    private val prefix = ByteArray(PREFIX_BYTES)
    private var prefixRead = 0
    private var remaining = 0L
    private var message: BodyCapture? = null

    fun add(content: ByteBuf) {
        var index = content.readerIndex()
        val end = content.writerIndex()
        while (index < end) {
            val current = message
            if (current == null) {
                prefix[prefixRead++] = content.getByte(index++)
                if (prefixRead == PREFIX_BYTES) startMessage()
            } else {
                val count = minOf(remaining, (end - index).toLong()).toInt()
                current.add(content.slice(index, count))
                index += count
                remaining -= count
                if (remaining == 0L) endMessage()
            }
        }
    }

    private fun startMessage() {
        prefixRead = 0
        remaining = (1 until PREFIX_BYTES).fold(0L) { length, i -> (length shl 8) or (prefix[i].toLong() and 0xff) }
        message = BodyCapture()
        if (remaining == 0L) endMessage()
    }

    private fun endMessage() {
        val done = message ?: return
        message = null
        onMessage(done)
    }

    private companion object {
        const val PREFIX_BYTES = 5
    }
}
