package appmonitor.sdk.core

/**
 * The `start` / `stop` of SDD 04 around one [MonitorClient] at a time: idle until started, a second
 * `start()` ignored while a client runs, a new client after `stop()`.
 */
internal class MonitorHolder {
    private class Running(
        val options: AppMonitorOptions,
        val client: MonitorClient,
    )

    private var current: Running? = null

    val client: MonitorClient? get() = synchronized(this) { current?.client }

    /** The options of the running client; null when idle or stopped. */
    val options: AppMonitorOptions? get() = synchronized(this) { current?.options }

    fun start(
        options: AppMonitorOptions,
        newClient: (AppMonitorOptions) -> MonitorClient,
    ) {
        synchronized(this) {
            if (!options.enabled || current != null) return
            current = Running(options, newClient(options).also { it.start() })
        }
    }

    fun stop() {
        val stopping = synchronized(this) { current.also { current = null } }
        stopping?.client?.stop()
    }
}

/** The process-wide monitor behind `AppMonitor` and [MonitorBridge]. */
internal val monitor: MonitorHolder = MonitorHolder()
