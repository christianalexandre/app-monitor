package appmonitor.desktop.proxy

import appmonitor.v1.Envelope
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.util.concurrent.GlobalEventExecutor
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The proxy of ADR-0101 on `127.0.0.1` only (A.1): plain HTTP and `CONNECT` with TLS interception, each exchange sent to
 * [record] as `PROXY` events. Single use; lifecycle in SDD 11.
 */
class ProxyServer(
    private val ca: ProxyCa,
    private val record: (Envelope) -> Unit,
    private val port: Int = DEFAULT_PORT,
    upstreamTrust: TrustManagerFactory? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val upstreamTls: SslContext =
        SslContextBuilder
            .forClient()
            .sslProvider(SslProvider.JDK)
            .trustManager(upstreamTrust)
            .endpointIdentificationAlgorithm("HTTPS")
            .build()
    private val lock = Any()
    private var group: EventLoopGroup? = null
    private var channel: Channel? = null
    private var used = false
    private val clients = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

    /** Binds and returns the port; throws [java.net.BindException] when it is taken. A second call throws. */
    fun start(): Int {
        synchronized(lock) {
            check(!used) { "a ProxyServer starts once" }
            used = true
            val loops = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
            group = loops
            val bound =
                try {
                    ServerBootstrap()
                        .group(loops)
                        .channel(NioServerSocketChannel::class.java)
                        .childHandler(
                            object : ChannelInitializer<SocketChannel>() {
                                override fun initChannel(ch: SocketChannel) {
                                    clients.add(ch)
                                    ch.pipeline().addLast(CODEC, HttpServerCodec())
                                    ch.pipeline().addLast(ClientHandler(ca, upstreamTls, record, clock))
                                }
                            },
                        ).bind(InetSocketAddress(HOST, port))
                        .sync()
                        .channel()
                } catch (e: Exception) {
                    loops.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
                    group = null
                    throw e
                }
            channel = bound
            return (bound.localAddress() as InetSocketAddress).port
        }
    }

    /** Closes the listener and every open connection, then waits for the event loops to end; idempotent. */
    fun stop() {
        val (loops, listener) =
            synchronized(lock) {
                (group to channel).also {
                    group = null
                    channel = null
                }
            }
        listener?.close()?.awaitUninterruptibly()
        clients.close().awaitUninterruptibly()
        loops?.shutdownGracefully(0, STOP_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)?.awaitUninterruptibly()
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 47476
        internal const val CODEC = "codec"

        /** How long [stop] waits for the event loops at most. */
        val STOP_TIMEOUT: Duration = 2.seconds
    }
}

/** `APPMONITOR_PROXY_PORT` when it is a port number, else [ProxyServer.DEFAULT_PORT]. */
fun proxyPort(env: (String) -> String? = System::getenv): Int =
    env("APPMONITOR_PROXY_PORT")?.trim()?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: ProxyServer.DEFAULT_PORT
