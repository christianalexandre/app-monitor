package appmonitor.tools.fakeclient

import appmonitor.desktop.server.LanAddresses
import appmonitor.protocol.DesktopAnswer
import appmonitor.protocol.DesktopSelector
import appmonitor.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * The desktop [DesktopSelector] picks among [Protocol.MDNS_SERVICE_TYPE] answers, named [serviceName] if
 * set, on any of [addresses], or `null` after [timeoutMs]. An address whose browser fails is skipped;
 * fails only when all of them do. [resolve] runs off the JmDNS listener thread: it may block.
 */
internal suspend fun discover(
    timeoutMs: Long,
    serviceName: String? = null,
    addresses: () -> List<Inet4Address> = LanAddresses::current,
    resolve: (ServiceEvent) -> Unit = { it.dns.requestServiceInfo(it.type, it.name, timeoutMs) },
    browse: (Inet4Address, ServiceListener) -> AutoCloseable = ::browse,
): Target? {
    val answers = Channel<Result<DesktopAnswer>>(Channel.UNLIMITED)
    val work = CoroutineScope(Dispatchers.IO)
    val listener =
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                work.launch { resolve(event) }
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                answers.trySend(Result.success(DesktopAnswer(event.name, host, info.port, info.getPropertyString(Protocol.MDNS_TXT_PATH))))
            }
        }
    val browsers = Browsers()
    // Opened off the timeout: JmDNS.create blocks and ignores cancellation.
    work.launch {
        try {
            val all = addresses()
            val failures = mutableListOf<Exception>()
            for (address in all) {
                val browser =
                    try {
                        browse(address, listener)
                    } catch (e: Exception) {
                        failures += e
                        continue
                    }
                if (!browsers.add(browser)) break
            }
            if (all.isNotEmpty() && failures.size == all.size) {
                answers.trySend(Result.failure(failures.first().apply { failures.drop(1).forEach(::addSuppressed) }))
            }
        } catch (e: Throwable) {
            answers.trySend(Result.failure(e))
        }
    }
    val selector = DesktopSelector(serviceName)
    try {
        val chosen =
            withTimeoutOrNull(timeoutMs) {
                var found: DesktopAnswer? = null
                while (found == null && !selector.hasCandidate) found = selector.offer(answers.receive().getOrThrow())
                found ?: withTimeoutOrNull(DesktopSelector.TXT_GRACE_MS) {
                    var complete: DesktopAnswer? = null
                    while (complete == null) complete = selector.offer(answers.receive().getOrThrow())
                    complete
                }
            } ?: selector.settle()
        return chosen?.let { Target(it.host, it.port, it.path ?: Protocol.WEBSOCKET_PATH) }
    } finally {
        work.cancel()
        answers.close()
        withContext(NonCancellable + Dispatchers.IO) { browsers.closeAll() }
    }
}

/** Browsers opened so far; one added after [closeAll] is closed on arrival. */
private class Browsers {
    private val open = mutableListOf<AutoCloseable>()
    private var closed = false

    fun add(browser: AutoCloseable): Boolean {
        synchronized(this) {
            if (!closed) return open.add(browser)
        }
        runCatching { browser.close() }
        return false
    }

    fun closeAll() {
        val toClose =
            synchronized(this) {
                closed = true
                open.toList()
            }
        toClose.forEach { runCatching { it.close() } }
    }
}

// A host name of its own: sharing the desktop's makes the two JmDNS instances conflict.
private fun browse(
    address: Inet4Address,
    listener: ServiceListener,
): JmDNS {
    val jmdns = JmDNS.create(address, "appmonitor-fake-client")
    try {
        jmdns.addServiceListener(Protocol.MDNS_SERVICE_TYPE, listener)
    } catch (e: Exception) {
        runCatching { jmdns.close() }
        throw e
    }
    return jmdns
}
