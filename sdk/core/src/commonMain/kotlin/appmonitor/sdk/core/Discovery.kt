package appmonitor.sdk.core

import appmonitor.protocol.DesktopAnswer
import appmonitor.protocol.DesktopSelector
import appmonitor.protocol.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Where the Android emulator reaches its host's loopback (ADR-0002). */
internal const val EMULATOR_HOST: String = "10.0.2.2"

/** A platform mDNS browser for [Protocol.MDNS_SERVICE_TYPE]; answers may repeat per name and come from any thread. */
internal fun interface MdnsBrowser {
    fun browse(onAnswer: (DesktopAnswer) -> Unit): AutoCloseable
}

/** Where the iOS simulator reaches its Mac's loopback: it shares the Mac's network stack (ADR-0002). */
internal const val SIMULATOR_HOST: String = "127.0.0.1"

/** Bounds of SDD 04's discovering state; tests shorten them. */
internal data class DiscoveryConfig(
    val mdnsBeforeFallback: Duration = 3.seconds,
    val txtGrace: Duration = DesktopSelector.TXT_GRACE_MS.milliseconds,
)

/**
 * The `discover` of [MonitorClient] for [options] (SDD 04): a manual host only, its pin unchecked; else mDNS
 * through [mdns] (null when it may not run), skipping desktops other than [pinned]'s (ADR-0168), then
 * [fallbackHost]: [EMULATOR_HOST], [SIMULATOR_HOST] or null elsewhere. */
internal fun discovery(
    options: AppMonitorOptions,
    mdns: () -> MdnsBrowser?,
    fallbackHost: String?,
    config: DiscoveryConfig = DiscoveryConfig(),
    log: (String) -> Unit = ::platformLog,
    pinned: () -> String? = { null },
): suspend () -> Endpoint? {
    val manual = options.host
    if (manual != null) return { Endpoint(manual, options.port, checksPin = false) }
    return {
        val browser = mdns()
        val pin = pinned()
        val found =
            try {
                when {
                    browser == null -> null

                    // Bounded so the fallback host is tried inside MonitorClient's discovery timeout.
                    fallbackHost != null -> withTimeoutOrNull(config.mdnsBeforeFallback) { discoverMdns(browser, config.txtGrace, pin) }

                    else -> discoverMdns(browser, config.txtGrace, pin)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("mDNS discovery failed: $e")
                null
            }
        found ?: fallbackHost?.let { Endpoint(it) }
    }
}

/** The desktop [DesktopSelector] picks from [browser]'s answers; suspends until one is found. Always closes the browser. */
internal suspend fun discoverMdns(
    browser: MdnsBrowser,
    txtGrace: Duration,
    pinned: String? = null,
): Endpoint {
    val answers = Channel<DesktopAnswer>(Channel.UNLIMITED)
    val selector = DesktopSelector(pinnedId = pinned)
    val browsing = browser.browse { answers.trySend(it) }
    try {
        var chosen: DesktopAnswer? = null
        while (chosen == null && !selector.hasCandidate) chosen = selector.offer(answers.receive())
        if (chosen == null) {
            chosen = withTimeoutOrNull(txtGrace) {
                var complete: DesktopAnswer? = null
                while (complete == null) complete = selector.offer(answers.receive())
                complete
            } ?: selector.settle()
        }
        val desktop = checkNotNull(chosen)
        return Endpoint(desktop.host, desktop.port, desktop.path ?: Protocol.WEBSOCKET_PATH)
    } finally {
        answers.close()
        runCatching { browsing.close() }
    }
}
