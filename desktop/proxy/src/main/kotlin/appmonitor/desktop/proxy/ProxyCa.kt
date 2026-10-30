package appmonitor.desktop.proxy

import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * The proxy's certificate authority (ADR-0101 C.1): one per data directory, its key beside the database, owner-only.
 * Leaf certificates are minted per host in memory and kept until a day before they expire.
 */
class ProxyCa private constructor(
    val certificate: X509Certificate,
    private val key: PrivateKey,
    private val clock: () -> Instant,
) {
    private class Leaf(
        val context: SslContext,
        val notAfter: Instant,
    )

    private val leaves = ConcurrentHashMap<String, Leaf>()

    /** A TLS server context presenting a leaf for [host], a DNS name or an IP literal; with [alpn], offering `h2` then `http/1.1`. */
    internal fun serverContext(
        host: String,
        alpn: Boolean = true,
    ): SslContext =
        leaves
            .compute("$alpn ${host.lowercase()}") { _, kept ->
                kept?.takeIf { clock() < it.notAfter.minus(RENEW_BEFORE) } ?: mint(host.lowercase(), alpn)
            }!!
            .context

    private fun mint(
        name: String,
        alpn: Boolean,
    ): Leaf {
        val keys = newKeyPair()
        val leaf = leaf(name, keys)
        val builder =
            SslContextBuilder
                .forServer(keys.private, leaf, certificate)
                .sslProvider(SslProvider.JDK)
        if (alpn) {
            builder.applicationProtocolConfig(
                ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1,
                ),
            )
        }
        return Leaf(builder.build(), leaf.notAfter.toInstant())
    }

    private fun leaf(
        host: String,
        keys: KeyPair,
    ): X509Certificate {
        val now = clock()
        val notAfter = minOf(now.plus(LEAF_VALIDITY), certificate.notAfter.toInstant())
        val altName = if (isIpLiteral(host)) GeneralName(GeneralName.iPAddress, host) else GeneralName(GeneralName.dNSName, host)
        val utils = JcaX509ExtensionUtils()
        val builder =
            JcaX509v3CertificateBuilder(
                certificate,
                serial(),
                Date.from(now.minus(BACKDATE)),
                Date.from(notAfter),
                X500Name("CN=$host"),
                keys.public,
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                .addExtension(Extension.subjectAlternativeName, false, GeneralNames(altName))
                .addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(keys.public))
                .addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(certificate))
        return sign(builder.build(JcaContentSignerBuilder(SIGNATURE).build(key)))
    }

    companion object {
        const val CERTIFICATE_FILE = "proxy-ca.pem"
        const val KEY_FILE = "proxy-ca-key.pem"

        private const val SIGNATURE = "SHA256withECDSA"
        private val CA_VALIDITY = Duration.ofDays(365)
        private val LEAF_VALIDITY = Duration.ofDays(30)
        private val BACKDATE = Duration.ofDays(1)

        // A CA or leaf this close to its end is replaced: a CA's leaves would outlive it.
        private val RENEW_BEFORE = Duration.ofDays(1)

        /** Reads the CA kept in [directory], or creates one when it is missing, unreadable or about to expire. */
        fun loadOrCreate(
            directory: Path,
            clock: () -> Instant = Instant::now,
        ): ProxyCa {
            load(directory, clock)?.let { return it }
            return create(directory, clock)
        }

        /** Deletes the CA files of [directory]; the next [loadOrCreate] makes a new one. */
        fun delete(directory: Path) {
            Files.deleteIfExists(directory.resolve(KEY_FILE))
            Files.deleteIfExists(directory.resolve(CERTIFICATE_FILE))
        }

        private fun load(
            directory: Path,
            clock: () -> Instant,
        ): ProxyCa? {
            val certificateFile = directory.resolve(CERTIFICATE_FILE)
            val keyFile = directory.resolve(KEY_FILE)
            if (Files.notExists(certificateFile) || Files.notExists(keyFile)) return null
            return try {
                val holder = PEMParser(StringReader(Files.readString(certificateFile))).use { it.readObject() } as? X509CertificateHolder
                val info = PEMParser(StringReader(Files.readString(keyFile))).use { it.readObject() } as? PrivateKeyInfo
                if (holder == null || info == null) return null
                val certificate = JcaX509CertificateConverter().getCertificate(holder)
                if (certificate.notAfter.toInstant().isBefore(clock().plus(RENEW_BEFORE))) return null
                ProxyCa(certificate, JcaPEMKeyConverter().getPrivateKey(info), clock)
            } catch (_: Exception) {
                null
            }
        }

        private fun create(
            directory: Path,
            clock: () -> Instant,
        ): ProxyCa {
            val now = clock()
            val keys = newKeyPair()
            val name = X500Name("CN=App Monitor proxy CA ${now.toString().take(10)}, O=App Monitor")
            val utils = JcaX509ExtensionUtils()
            val builder =
                JcaX509v3CertificateBuilder(
                    name,
                    serial(),
                    Date.from(now.minus(BACKDATE)),
                    Date.from(now.plus(CA_VALIDITY)),
                    name,
                    keys.public,
                ).addExtension(Extension.basicConstraints, true, BasicConstraints(0))
                    .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
                    .addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(keys.public))
            val certificate = sign(builder.build(JcaContentSignerBuilder(SIGNATURE).build(keys.private)))
            Files.createDirectories(directory)
            delete(directory)
            writeOwnerOnly(directory.resolve(KEY_FILE), pemOf(JcaPKCS8Generator(keys.private, null)))
            writeOwnerOnly(directory.resolve(CERTIFICATE_FILE), pemOf(certificate))
            return ProxyCa(certificate, keys.private, clock)
        }

        // Same protection as the database (ADR-0081): the mode is set before the key is written.
        private fun writeOwnerOnly(
            file: Path,
            text: String,
        ) {
            Files.createFile(file)
            if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
            Files.writeString(file, text)
        }

        private fun newKeyPair(): KeyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()

        private fun serial(): BigInteger = BigInteger(127, SecureRandom())

        private fun sign(holder: X509CertificateHolder): X509Certificate = JcaX509CertificateConverter().getCertificate(holder)

        private fun pemOf(value: Any): String = StringWriter().also { out -> JcaPEMWriter(out).use { it.writeObject(value) } }.toString()

        private fun isIpLiteral(host: String): Boolean = host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }
}
