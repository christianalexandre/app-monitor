package appmonitor.desktop.proxy

import appmonitor.desktop.storage.ExchangeDetail
import appmonitor.desktop.storage.Storage
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** An HTTP origin on `127.0.0.1` answering `200` with `method path body`, over TLS when [tls] is given. */
internal class Origin(
    tls: SslContext?,
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
                        ch.pipeline().addLast(HttpServerCodec(), HttpObjectAggregator(1 shl 20), Answer())
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

    private class Answer : SimpleChannelInboundHandler<FullHttpRequest>() {
        override fun channelRead0(
            ctx: ChannelHandlerContext,
            msg: FullHttpRequest,
        ) {
            val text = "${msg.method()} ${msg.uri()} ${msg.content().toString(Charsets.UTF_8)}"
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(text, Charsets.UTF_8))
            response.headers().set("Content-Type", "text/plain")
            HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
            ctx.writeAndFlush(response)
        }
    }
}

/** Trusts only [ca], as the proxy would trust an origin's real chain. */
internal fun trusting(ca: ProxyCa): TrustManagerFactory {
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    store.setCertificateEntry("origin", ca.certificate)
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
}

internal class Curl(
    val exit: Int,
    val output: String,
)

internal fun curl(vararg args: String): Curl {
    val process = ProcessBuilder(listOf("curl", "-sS", "--max-time", "20") + args).redirectErrorStream(true).start()
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "curl did not finish")
    return Curl(process.exitValue(), process.inputStream.readBytes().decodeToString())
}

/** A proxy writing to a store, both in a temporary data directory, closed after [block]. */
@OptIn(ExperimentalPathApi::class)
internal fun withProxy(
    upstreamTrust: TrustManagerFactory? = null,
    block: suspend Proxy.() -> Unit,
) {
    val dir = createTempDirectory("proxy-test")
    val storage = Storage.open(dir.resolve("data"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val ca = ProxyCa.loadOrCreate(dir.resolve("data"))
        val recording = ProxyRecording(storage, scope)
        val server = ProxyServer(ca, recording::record, port = 0, upstreamTrust = upstreamTrust)
        val port = server.start()
        runBlocking {
            try {
                Proxy(port, dir.resolve("data").resolve(ProxyCa.CERTIFICATE_FILE), storage, dir).block()
            } finally {
                server.stop()
                recording.close()
            }
        }
    } finally {
        scope.cancel()
        runBlocking { storage.close(5.seconds) }
        dir.deleteRecursively()
    }
}

internal class Proxy(
    val port: Int,
    val caFile: Path,
    val storage: Storage,
    val scratch: Path,
) {
    val url: String get() = "http://127.0.0.1:$port"

    /** The stored exchanges once [count] of them have ended. */
    suspend fun ended(count: Int): List<ExchangeDetail> =
        withTimeout(10.seconds) {
            while (true) {
                storage.flush()
                val sessions = storage.sessions(storage.runId)
                val details = sessions.flatMap { session -> storage.exchangesOfSession(session.id).mapNotNull { storage.exchange(it.id) } }
                if (details.count { it.exchange.endedAtMs != null } >= count) return@withTimeout details
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    fun file(name: String): Path = scratch.resolve(name).also { Files.deleteIfExists(it) }
}
