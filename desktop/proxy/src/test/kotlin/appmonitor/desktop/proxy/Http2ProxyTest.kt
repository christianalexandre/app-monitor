package appmonitor.desktop.proxy

import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.groupByEndpoint
import appmonitor.v1.WebSocketFrame
import io.grpc.CallOptions
import io.grpc.Grpc
import io.grpc.HttpConnectProxiedSocketAddress
import io.grpc.InsecureChannelCredentials
import io.grpc.InsecureServerCredentials
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Http2ProxyTest {
    private val originCa = ProxyCa.loadOrCreate(createTempDirectory("origin-ca"))

    @Test
    fun `curl over http2 gets its response and the exchange is stored with its trailers`() {
        Http2Origin(originCa.serverContext("127.0.0.1")).use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                val target = "https://127.0.0.1:${origin.port}/h2?x=1"
                val result =
                    curl("--proxy", url, "--http2", "--cacert", caFile.toString(), "-w", " %{http_version}", "--data", "ping", target)

                assertEquals(0, result.exit, result.output)
                assertEquals("POST /h2?x=1 ping 2", result.output)
                val detail = ended(1).single()
                assertEquals("POST", detail.exchange.method)
                assertEquals(target, detail.exchange.url)
                assertEquals(200, detail.exchange.statusCode)
                assertEquals("ping", detail.request!!.body.utf8())
                assertEquals("POST /h2?x=1 ping", detail.response!!.body.utf8())
                val headers = detail.response!!.headers.associate { it.name to it.value_ }
                assertEquals("done", headers["x-trailer"])
                assertTrue(headers.keys.none { it.startsWith(":") }, headers.toString())
            }
        }
    }

    @Test
    fun `an http2 client to an origin without http2 gets a 502 and a failed exchange`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                val result =
                    curl(
                        "--proxy",
                        url,
                        "--http2",
                        "--cacert",
                        caFile.toString(),
                        "-w",
                        "%{http_code}",
                        "https://127.0.0.1:${origin.port}/old",
                    )

                assertTrue(result.output.endsWith("502"), result.output)
                val exchange = ended(1).single().exchange
                assertTrue(exchange.failed)
                assertEquals("127.0.0.1:${origin.port} does not offer HTTP/2", exchange.error)
            }
        }
    }

    @Test
    fun `an http2 stream to a refused port gets a 502 and a failed exchange`() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        withProxy {
            val result =
                curl("--proxy", url, "--http2", "--cacert", caFile.toString(), "-w", "%{http_code}", "https://127.0.0.1:$closed/none")

            assertTrue(result.output.endsWith("502"), result.output)
            val exchange = ended(1).single().exchange
            assertTrue(exchange.error!!.startsWith("Could not connect to 127.0.0.1:$closed"), exchange.error)
        }
    }

    @Test
    fun `a grpc unary call is stored with its messages as frames and its grpc-status`() {
        Echo().use { echo ->
            withProxy {
                echo.channel(port).use { (channel) ->
                    val reply = ClientCalls.blockingUnaryCall(channel, Echo.UNARY, options(), "ping".toByteArray())
                    assertEquals("echo:ping", reply.decodeToString())
                }
                val detail = ended(1).single()
                assertEquals("POST", detail.exchange.method)
                assertEquals("http://127.0.0.1:${echo.port}/test.Echo/Unary", detail.exchange.url)
                assertEquals(200, detail.exchange.statusCode)
                assertEquals(0, detail.exchange.grpcStatus)
                assertFalse(detail.exchange.isError)
                assertEquals(
                    "application/grpc",
                    detail.request!!
                        .headers
                        .first { it.name == "content-type" }
                        .value_,
                )
                assertEquals(
                    "0",
                    detail.response!!
                        .headers
                        .last { it.name == "grpc-status" }
                        .value_,
                )
                assertEquals(
                    listOf(SENT to "ping", RECEIVED to "echo:ping"),
                    storage.frames(detail.exchange.id).map { it.direction to it.payload.utf8() },
                )
            }
        }
    }

    @Test
    fun `a grpc server streaming call stores each message`() {
        Echo().use { echo ->
            withProxy {
                echo.channel(port).use { (channel) ->
                    val replies = ClientCalls.blockingServerStreamingCall(channel, Echo.STREAM, options(), "n".toByteArray())
                    assertEquals(listOf("n1", "n2", "n3"), replies.asSequence().map { it.decodeToString() }.toList())
                }
                val detail = ended(1).single()
                assertEquals(
                    "0",
                    detail.response!!
                        .headers
                        .last { it.name == "grpc-status" }
                        .value_,
                )
                val frames = storage.frames(detail.exchange.id)
                assertEquals(listOf(SENT, RECEIVED, RECEIVED, RECEIVED), frames.map { it.direction })
                assertEquals(listOf("n", "n1", "n2", "n3"), frames.map { it.payload.utf8() })
                assertTrue(frames.all { it.opcode == WebSocketFrame.Opcode.OPCODE_BINARY && it.payloadSize == it.payload.size.toLong() })
            }
        }
    }

    @Test
    fun `a grpc error status is stored from its trailers`() {
        Echo().use { echo ->
            withProxy {
                echo.channel(port).use { (channel) ->
                    val error =
                        assertFailsWith<StatusRuntimeException> {
                            ClientCalls.blockingUnaryCall(channel, Echo.MISSING, options(), "x".toByteArray())
                        }
                    assertEquals(Status.Code.NOT_FOUND, error.status.code)
                }
                val detail = ended(1).single()
                val headers = detail.response!!.headers.associate { it.name to it.value_ }
                assertEquals("5", headers["grpc-status"])
                assertEquals("no such thing", headers["grpc-message"])
                assertEquals(200, detail.exchange.statusCode)
                assertEquals(5, detail.exchange.grpcStatus)
                assertTrue(detail.exchange.isError)
                assertEquals(
                    1L,
                    storage
                        .groupByEndpoint(ExchangeFilter())
                        .endpoints
                        .single()
                        .errors,
                )
            }
        }
    }

    private fun options() = CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS)

    private companion object {
        val SENT = WebSocketFrame.Direction.DIRECTION_SENT
        val RECEIVED = WebSocketFrame.Direction.DIRECTION_RECEIVED
    }
}

/** A grpc-java server on `127.0.0.1` with byte array messages: `Unary` echoes, `Stream` answers three times, `Missing` fails. */
private class Echo : AutoCloseable {
    private val server: Server =
        NettyServerBuilder
            .forAddress(InetSocketAddress("127.0.0.1", 0), InsecureServerCredentials.create())
            .addService(
                ServerServiceDefinition
                    .builder("test.Echo")
                    .addMethod(
                        UNARY,
                        ServerCalls.asyncUnaryCall { request, reply ->
                            reply.onNext("echo:".toByteArray() + request)
                            reply.onCompleted()
                        },
                    ).addMethod(
                        STREAM,
                        ServerCalls.asyncServerStreamingCall { request, reply ->
                            for (i in 1..3) reply.onNext(request + "$i".toByteArray())
                            reply.onCompleted()
                        },
                    ).addMethod(
                        MISSING,
                        ServerCalls.asyncUnaryCall { _, reply ->
                            reply.onError(Status.NOT_FOUND.withDescription("no such thing").asRuntimeException())
                        },
                    ).build(),
            ).build()
            .start()

    val port: Int get() = server.port

    /** A plaintext channel whose every call goes through the proxy on [proxyPort] with `CONNECT`. */
    fun channel(proxyPort: Int): Closing =
        Closing(
            Grpc
                .newChannelBuilderForAddress("127.0.0.1", port, InsecureChannelCredentials.create())
                .proxyDetector { address ->
                    HttpConnectProxiedSocketAddress
                        .newBuilder()
                        .setTargetAddress(address as InetSocketAddress)
                        .setProxyAddress(InetSocketAddress("127.0.0.1", proxyPort))
                        .build()
                }.build(),
        )

    override fun close() {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    data class Closing(
        val channel: ManagedChannel,
    ) : AutoCloseable {
        override fun close() {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    companion object {
        private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

        private fun method(
            name: String,
            type: MethodDescriptor.MethodType,
        ) = MethodDescriptor
            .newBuilder(Bytes, Bytes)
            .setType(type)
            .setFullMethodName("test.Echo/$name")
            .build()

        val UNARY = method("Unary", MethodDescriptor.MethodType.UNARY)
        val STREAM = method("Stream", MethodDescriptor.MethodType.SERVER_STREAMING)
        val MISSING = method("Missing", MethodDescriptor.MethodType.UNARY)
    }
}
