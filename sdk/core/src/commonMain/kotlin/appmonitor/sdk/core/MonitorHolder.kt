package appmonitor.sdk.core

/**
 * The `start` / `stop` of SDD 04 around one [MonitorClient] at a time: idle until started, a second
 * `start()` ignored while a client runs, a new client after `stop()`.
 */
internal class MonitorHolder {
    private var current: MonitorClient? = null

    val client: MonitorClient? get() = synchronized(this) { current }

    fun start(
        options: AppMonitorOptions,
        newClient: (AppMonitorOptions) -> MonitorClient,
    ) {
        synchronized(this) {
            if (!options.enabled || current != null) return
            current = newClient(options).also { it.start() }
        }
    }

    fun stop() {
        val stopping = synchronized(this) { current.also { current = null } }
        stopping?.stop()
    }
}
