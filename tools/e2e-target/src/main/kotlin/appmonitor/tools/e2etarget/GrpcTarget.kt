package appmonitor.tools.e2etarget

import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.TlsServerCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ServerCalls
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringWriter
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * The TLS gRPC origin of `make e2e-ios` (ADR-0108): `grpcbin.GRPCBin/DummyUnary` echoes its message, as grpcb.in
 * does, behind a self-signed certificate for `127.0.0.1` that [trustStore] holds for the desktop's proxy.
 */
internal class GrpcTarget : AutoCloseable {
    private val certificate: X509Certificate
    private val server: Server

    init {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val name = X500Name("CN=$HOST, O=App Monitor e2e")
        val now = Instant.now()
        val holder =
            JcaX509v3CertificateBuilder(
                name,
                BigInteger(127, SecureRandom()),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(2))),
                name,
                keys.public,
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.iPAddress, HOST)))
                .build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))
        certificate = JcaX509CertificateConverter().getCertificate(holder)
        val credentials =
            TlsServerCredentials.create(
                ByteArrayInputStream(pemOf(certificate).toByteArray()),
                ByteArrayInputStream(pemOf(JcaPKCS8Generator(keys.private, null)).toByteArray()),
            )
        server =
            NettyServerBuilder
                .forAddress(InetSocketAddress(HOST, 0), credentials)
                .addService(
                    ServerServiceDefinition
                        .builder("grpcbin.GRPCBin")
                        .addMethod(
                            DUMMY_UNARY,
                            ServerCalls.asyncUnaryCall { request, reply ->
                                reply.onNext(request)
                                reply.onCompleted()
                            },
                        ).build(),
                ).build()
                .start()
    }

    val port: Int get() = server.port

    /** Writes a PKCS#12 trust store holding only this origin's certificate, password [TRUST_STORE_PASSWORD]. */
    fun trustStore(file: Path) {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setCertificateEntry("e2e-grpc", certificate)
        Files.newOutputStream(file).use { store.store(it, TRUST_STORE_PASSWORD.toCharArray()) }
    }

    override fun close() {
        server.shutdownNow()
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val TRUST_STORE_PASSWORD = "e2e-target"

        // The iOS sample calls this path (GrpcCoreCall.swift); renaming it breaks `make e2e-ios`.
        val DUMMY_UNARY: MethodDescriptor<ByteArray, ByteArray> =
            MethodDescriptor
                .newBuilder(Bytes, Bytes)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("grpcbin.GRPCBin/DummyUnary")
                .build()

        private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

        private fun pemOf(value: Any): String = StringWriter().also { out -> JcaPEMWriter(out).use { it.writeObject(value) } }.toString()
    }
}
