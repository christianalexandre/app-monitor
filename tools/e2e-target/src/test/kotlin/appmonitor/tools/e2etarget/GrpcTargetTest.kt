package appmonitor.tools.e2etarget

import io.grpc.CallOptions
import io.grpc.Grpc
import io.grpc.TlsChannelCredentials
import io.grpc.stub.ClientCalls
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GrpcTargetTest {
    @Test
    fun `echoes a unary call over tls trusted by the written trust store`() {
        val store = createTempDirectory("grpc-target").resolve("trust.p12")
        GrpcTarget().use { target ->
            target.trustStore(store)
            val channel =
                Grpc
                    .newChannelBuilderForAddress(
                        GrpcTarget.HOST,
                        target.port,
                        TlsChannelCredentials.newBuilder().trustManager(*trusting(store).trustManagers).build(),
                    ).build()
            try {
                val message = "hello".toByteArray()
                val reply = ClientCalls.blockingUnaryCall(channel, GrpcTarget.DUMMY_UNARY, CallOptions.DEFAULT, message)
                assertContentEquals(message, reply)
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `options take the port and the trust store in any order`() {
        assertEquals(Options(0, Path.of("t.p12")), options(arrayOf("--grpc-trust", "t.p12", "--port", "0")))
        assertEquals(Options(), options(emptyArray()))
        listOf(arrayOf("--port"), arrayOf("--port", "70000"), arrayOf("--grpc", "x"), arrayOf("--port", "1", "--grpc-trust"))
            .forEach { assertNull(options(it), it.joinToString(" ")) }
    }

    private fun trusting(file: Path): TrustManagerFactory {
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(file).use { store.load(it, GrpcTarget.TRUST_STORE_PASSWORD.toCharArray()) }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    }
}
