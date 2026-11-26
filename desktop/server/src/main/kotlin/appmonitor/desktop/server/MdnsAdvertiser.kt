package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Publishes the server as [Protocol.MDNS_SERVICE_TYPE] on every LAN address that takes it (ADR-0174), and follows the
 * machine's addresses on each [refresh] (ADR-0226).
 */
internal class MdnsAdvertiser private constructor(
    registered: List<Pair<InetAddress, AutoCloseable>>,
    failed: List<Pair<InetAddress, Exception>>,
    private val closeTimeout: Duration,
    private val currentAddresses: () -> List<InetAddress>,
    private val register: (InetAddress) -> AutoCloseable,
) : AutoCloseable {
    private val lock = Any()
    private var registrations = registered
    private var failures = failed
    private var closed = false

    val addresses: List<InetAddress> get() = synchronized(lock) { registrations.map { it.first } }

    /**
     * Registers the addresses that appeared since the last call and closes, in the background, those that went away; an
     * address that failed is not tried again until it goes away and comes back. Nothing after [close].
     */
    fun refresh() {
        val current = currentAddresses()
        val (known, gone) =
            synchronized(lock) {
                if (closed) return
                failures = failures.filter { it.first in current }
                (registrations.map { it.first } + failures.map { it.first }) to registrations.filter { it.first !in current }
            }
        gone.forEach { (_, registration) ->
            thread(isDaemon = true, name = "app-monitor-mdns-close") { runCatching { registration.close() } }
        }
        val added = ArrayList<Pair<InetAddress, AutoCloseable>>()
        val failed = ArrayList<Pair<InetAddress, Exception>>()
        for (address in current.filter { it !in known }) {
            try {
                added += address to register(address)
            } catch (e: Exception) {
                failed += address to e
            }
        }
        val late =
            synchronized(lock) {
                if (closed) return@synchronized added
                registrations = registrations.filter { it.first in current } + added
                failures = failures + failed
                emptyList()
            }
        late.forEach { (_, registration) -> runCatching { registration.close() } }
    }

    /** What a developer should know about the advertising, or null when every LAN address has it. */
    val problem: String?
        get() {
            val (registrations, failures) = synchronized(lock) { registrations to failures }
            val failed =
                failures.joinToString(", ", "mDNS failed on ") { (address, failure) ->
                    "${address.hostAddress} (${failure.message ?: failure::class.simpleName})"
                }
            return when {
                registrations.isEmpty() && failures.isEmpty() -> "no LAN address to advertise on, $NEEDS_HOST"
                registrations.isEmpty() -> "$failed, $NEEDS_HOST"
                failures.isNotEmpty() -> failed
                else -> null
            }
        }

    /**
     * Closes every registration at once, each taking seconds (ADR-0174), and waits at most the close timeout for them;
     * then rethrows the first failure with the others suppressed. One still closing goes on in the background.
     */
    override fun close() {
        val registrations =
            synchronized(lock) {
                closed = true
                registrations
            }
        val failed = ConcurrentLinkedQueue<Throwable>()
        val closing =
            registrations.map { (address, registration) ->
                address to
                    thread(isDaemon = true, name = "app-monitor-mdns-close") {
                        try {
                            registration.close()
                        } catch (e: Throwable) {
                            failed += e
                        }
                    }
            }
        val deadline = TimeSource.Monotonic.markNow() + closeTimeout
        // Thread.join(0) waits forever.
        closing.forEach { (_, worker) -> worker.join((-deadline.elapsedNow()).inWholeMilliseconds.coerceAtLeast(1)) }
        val stuck = closing.filter { (_, worker) -> worker.isAlive }.map { (address, _) -> address.hostAddress }
        if (stuck.isNotEmpty()) failed += IOException("mDNS close did not finish within $closeTimeout on $stuck")
        val failure = failed.poll() ?: return
        failed.forEach { failure.addSuppressed(it) }
        throw failure
    }

    companion object {
        // Under STOP_TIMEOUT by more than the engine's stop: measured 2 to 3 s per address (ADR-0174).
        private val CLOSE_TIMEOUT: Duration = 3500.milliseconds

        private const val NEEDS_HOST = "devices need this machine's address set"

        /** An address that fails is skipped and reported in [problem]; an [Error] closes the ones registered and rethrows. */
        fun start(
            serviceName: String,
            port: Int,
            desktopId: String? = null,
            currentAddresses: () -> List<InetAddress> = LanAddresses::current,
            addresses: List<InetAddress> = currentAddresses(),
            closeTimeout: Duration = CLOSE_TIMEOUT,
            register: (InetAddress) -> AutoCloseable = { register(it, serviceName, port, desktopId) },
        ): MdnsAdvertiser {
            val registrations = mutableListOf<Pair<InetAddress, AutoCloseable>>()
            val failures = mutableListOf<Pair<InetAddress, Exception>>()
            try {
                for (address in addresses) {
                    try {
                        registrations += address to register(address)
                    } catch (e: Exception) {
                        failures += address to e
                    }
                }
            } catch (e: Throwable) {
                registrations.forEach { (_, registration) -> runCatching { registration.close() } }
                throw e
            }
            return MdnsAdvertiser(registrations, failures, closeTimeout, currentAddresses, register)
        }

        /** One per address: two instances on one segment sharing a host name conflict over its record (ADR-0174). */
        internal fun hostName(address: InetAddress): String = "appmonitor-" + address.hostAddress.replace('.', '-')

        private fun register(
            address: InetAddress,
            serviceName: String,
            port: Int,
            desktopId: String?,
        ): JmDNS {
            val jmdns = JmDNS.create(address, hostName(address))
            try {
                jmdns.registerService(serviceInfo(serviceName, port, desktopId))
            } catch (e: Exception) {
                runCatching { jmdns.close() }
                throw e
            }
            return jmdns
        }

        /** TXT `v`, `path`, and `id` when there is one (ADR-0168). */
        internal fun serviceInfo(
            serviceName: String,
            port: Int,
            desktopId: String?,
        ): ServiceInfo =
            ServiceInfo.create(
                Protocol.MDNS_SERVICE_TYPE,
                serviceName,
                port,
                0,
                0,
                mapOf("v" to Protocol.VERSION.toString(), Protocol.MDNS_TXT_PATH to Protocol.WEBSOCKET_PATH) +
                    listOfNotNull(desktopId?.let { Protocol.MDNS_TXT_ID to it }),
            )
    }
}
