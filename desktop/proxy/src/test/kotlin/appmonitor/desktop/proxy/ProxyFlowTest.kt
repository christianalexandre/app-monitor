package appmonitor.desktop.proxy

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamChannel
import io.netty.handler.ssl.SslContext
import io.netty.util.ReferenceCountUtil
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How requests and responses flow through the proxy: an early answer, and a slow side holding the fast one. */
class ProxyFlowTest {
    private val originCa = ProxyCa.loadOrCreate(createTempDirectory("origin-ca"))

    @Test
    fun `an answer before the request's end drops the rest of its body and the next request is served`() {
        TestOrigin(null) { EarlyAnswer() }.use { origin ->
            withProxy {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 10_000
                    val out = socket.getOutputStream()
                    val input = socket.getInputStream()
                    val base = "http://127.0.0.1:${origin.port}"
                    out.write("POST $base/upload HTTP/1.1\r\nHost: x\r\nContent-Length: $UPLOAD\r\n\r\n".toByteArray())
                    out.write(ByteArray(UPLOAD / 4))
                    out.flush()
                    assertTrue(input.response().startsWith("HTTP/1.1 413"))
                    out.write(ByteArray(UPLOAD - UPLOAD / 4))
                    out.write("GET $base/after HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    out.flush()

                    assertEquals("HTTP/1.1 200 OK", input.response().lineSequence().first())
                    val stored = ended(2).map { "${it.exchange.url} ${it.exchange.statusCode}" }.sorted()
                    assertEquals(listOf("$base/after 200", "$base/upload 413"), stored)
                }
            }
        }
    }

    @Test
    fun `a client that does not read holds an http1 origin`() {
        val counter = AtomicLong()
        TestOrigin(null) { StreamHttp1(counter) }.use { origin ->
            withProxy {
                Socket("127.0.0.1", port).use { socket ->
                    socket.receiveBufferSize = 64 * 1024
                    socket.getOutputStream().write("GET http://127.0.0.1:${origin.port}/big HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    Thread.sleep(PAUSE_MS)

                    assertTrue(counter.get() < STREAMED / 4, "origin wrote ${counter.get()} bytes to a client reading none")
                }
            }
        }
    }

    @Test
    fun `a slow http2 client holds its origin`() {
        val counter = AtomicLong()
        TestOrigin(originCa.serverContext("127.0.0.1"), http2 = true) { StreamHttp2(counter) }.use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                val target = "https://127.0.0.1:${origin.port}/big"
                val result =
                    curl(
                        "--proxy",
                        url,
                        "--http2",
                        "--cacert",
                        caFile.toString(),
                        "--limit-rate",
                        "100k",
                        "-o",
                        file("big").toString(),
                        "--max-time",
                        "2",
                        target,
                    )

                assertEquals(28, result.exit, result.output)
                assertTrue(counter.get() < STREAMED / 4, "origin wrote ${counter.get()} bytes to a client reading 100 KiB/s")
            }
        }
    }

    /** Answers `/upload` with `413` at its headers, without reading its body; any other request with `200` at its end. */
    private class EarlyAnswer : ChannelInboundHandlerAdapter() {
        private var answerAtEnd = false

        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            if (msg is HttpRequest) {
                answerAtEnd = msg.uri() != "/upload"
                if (!answerAtEnd) ctx.writeAndFlush(empty(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE))
            }
            if (msg is LastHttpContent && answerAtEnd) {
                answerAtEnd = false
                ctx.writeAndFlush(empty(HttpResponseStatus.OK))
            }
            ReferenceCountUtil.release(msg)
        }

        private fun empty(status: HttpResponseStatus) =
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status).also { HttpUtil.setContentLength(it, 0) }
    }

    /** Answers each request with [STREAMED] bytes, written only while the connection takes them; [counter] counts them. */
    private class StreamHttp1(
        private val counter: AtomicLong,
    ) : ChannelInboundHandlerAdapter() {
        private var remaining = 0L

        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            if (msg is HttpRequest) {
                ctx.write(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK).also { HttpUtil.setContentLength(it, STREAMED) })
                remaining = STREAMED
                pump(ctx)
            }
            ReferenceCountUtil.release(msg)
        }

        override fun channelWritabilityChanged(ctx: ChannelHandlerContext) = pump(ctx)

        private fun pump(ctx: ChannelHandlerContext) {
            while (remaining > 0 && ctx.channel().isWritable) {
                remaining -= CHUNK
                counter.addAndGet(CHUNK.toLong())
                val bytes = Unpooled.wrappedBuffer(ByteArray(CHUNK))
                ctx.write(if (remaining == 0L) DefaultLastHttpContent(bytes) else DefaultHttpContent(bytes))
            }
            ctx.flush()
        }
    }

    /** As [StreamHttp1] on one HTTP/2 stream: its writability follows the peer's flow control window. */
    private class StreamHttp2(
        private val counter: AtomicLong,
    ) : ChannelInboundHandlerAdapter() {
        private var remaining = 0L

        override fun channelRead(
            ctx: ChannelHandlerContext,
            msg: Any,
        ) {
            if (msg is Http2HeadersFrame && remaining == 0L) {
                ctx.write(DefaultHttp2HeadersFrame(DefaultHttp2Headers().status("200")))
                remaining = STREAMED
                pump(ctx)
            }
            ReferenceCountUtil.release(msg)
        }

        override fun channelWritabilityChanged(ctx: ChannelHandlerContext) = pump(ctx)

        private fun pump(ctx: ChannelHandlerContext) {
            while (remaining > 0 && ctx.channel().isWritable) {
                remaining -= CHUNK
                counter.addAndGet(CHUNK.toLong())
                ctx.write(DefaultHttp2DataFrame(Unpooled.wrappedBuffer(ByteArray(CHUNK)), remaining == 0L))
            }
            ctx.flush()
        }
    }

    /** An origin on `127.0.0.1`, HTTP/1.1 or HTTP/2, over TLS when [tls] is given, each connection or stream to [handler]. */
    private class TestOrigin(
        tls: SslContext?,
        http2: Boolean = false,
        handler: () -> ChannelHandler,
    ) : AutoCloseable {
        private val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        private val channel: Channel =
            ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            tls?.let { ch.pipeline().addLast(it.newHandler(ch.alloc())) }
                            if (!http2) {
                                ch.pipeline().addLast(HttpServerCodec(), handler())
                                return
                            }
                            ch.pipeline().addLast(Http2FrameCodecBuilder.forServer().build())
                            ch.pipeline().addLast(
                                Http2MultiplexHandler(
                                    object : ChannelInitializer<Http2StreamChannel>() {
                                        override fun initChannel(ch: Http2StreamChannel) {
                                            ch.pipeline().addLast(handler())
                                        }
                                    },
                                ),
                            )
                        }
                    },
                ).bind(InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel()

        val port: Int get() = (channel.localAddress() as InetSocketAddress).port

        override fun close() {
            channel.close().sync()
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()
        }
    }

    private companion object {
        const val UPLOAD = 4 * 1024 * 1024
        const val CHUNK = 64 * 1024
        const val STREAMED = 256L * 1024 * 1024
        const val PAUSE_MS = 1_500L

        /** One response's head and its empty body, read up to the blank line. */
        fun InputStream.response(): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = read()
                check(byte >= 0) { "closed after: $head" }
                head.append(byte.toChar())
            }
            return head.toString()
        }
    }
}
