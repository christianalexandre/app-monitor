package appmonitor.desktop.proxy

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoop
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandler
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Whether an origin's TLS negotiates HTTP/2, asked with one handshake of its own and kept per host and port, so a tunnel's
 * leaf offers what the origin does (ADR-0195). No answer in [timeoutMs] counts as yes and is not kept: the relay reports it.
 */
internal class OriginAlpn(
    private val tls: SslContext,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val known = ConcurrentHashMap<String, Boolean>()

    /** Calls [then] on [loop] once, at once when the answer is known. */
    fun ask(
        host: String,
        port: Int,
        loop: EventLoop,
        then: (http2: Boolean) -> Unit,
    ) {
        val key = key(host, port)
        known[key]?.let { return then(it) }
        var answered = false

        fun answer(http2: Boolean) {
            if (answered) return
            answered = true
            then(http2)
        }
        val connecting =
            Bootstrap()
                .group(loop)
                .channel(NioSocketChannel::class.java)
                .handler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            val handler = tls.newHandler(ch.alloc(), host, port)
                            handler.handshakeFuture().addListener { done ->
                                if (done.isSuccess) {
                                    val http2 = handler.applicationProtocol() == ApplicationProtocolNames.HTTP_2
                                    known[key] = http2
                                    answer(http2)
                                } else {
                                    answer(true)
                                }
                                ch.close()
                            }
                            ch.pipeline().addLast(handler, Discard())
                        }
                    },
                ).connect(host, port)
        connecting.addListener(ChannelFutureListener { if (!it.isSuccess) answer(true) })
        loop.schedule(
            {
                answer(true)
                connecting.channel().close()
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun remember(
        host: String,
        port: Int,
        http2: Boolean,
    ) {
        known[key(host, port)] = http2
    }

    private fun key(
        host: String,
        port: Int,
    ) = "${host.lowercase()}:$port"

    private class Discard : ChannelInboundHandlerAdapter() {
        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            ReferenceCountUtil.release(msg)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun exceptionCaught(
            ctx: ChannelHandlerContext,
            cause: Throwable,
        ) {
            ctx.close()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}

/**
 * A tunnel's first bytes: TLS waits here for [ask], then goes to a handler from [leaf]'s context (ADR-0195); anything else
 * goes on as it is, the origin not asked, since a plain port may never answer a handshake.
 */
internal class TunnelTls(
    private val ask: (then: (http2: Boolean) -> Unit) -> Unit,
    private val leaf: (http2: Boolean) -> SslContext,
) : ByteToMessageDecoder() {
    private var decided = false

    override fun decode(
        ctx: ChannelHandlerContext,
        input: ByteBuf,
        out: MutableList<Any>,
    ) {
        if (decided || input.readableBytes() < TLS_RECORD_HEADER_BYTES) return
        decided = true
        if (!SslHandler.isEncrypted(input, false)) {
            ctx.pipeline().remove(this)
            return
        }
        ask { http2 ->
            if (!ctx.isRemoved && ctx.channel().isActive) {
                ctx.pipeline().addAfter(ctx.name(), "tls", leaf(http2).newHandler(ctx.alloc()))
                ctx.pipeline().remove(this)
            }
        }
    }

    private companion object {
        const val TLS_RECORD_HEADER_BYTES = 5
    }
}
