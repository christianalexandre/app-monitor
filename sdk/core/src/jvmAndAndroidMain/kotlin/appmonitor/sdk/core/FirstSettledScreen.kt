package appmonitor.sdk.core

/**
 * Calls [ask] once, on the first screen that stays resumed [settleMs] (ADR-0156); with several resumed at once
 * (multi-window), on the last one. [answered] runs once, on the first screen resumed after [ask]: the dialog is
 * gone. [schedule] runs its block after a delay and returns its cancel; every call comes from Android's main thread.
 */
internal class FirstSettledScreen<S : Any>(
    private val settleMs: Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> () -> Unit,
    private val gone: (S) -> Boolean,
    private val ask: (S) -> Unit,
    private val answered: (S) -> Unit = {},
) {
    private val resumed = mutableListOf<S>()
    private var cancel: (() -> Unit)? = null
    private var asked = false
    private var done = false

    fun resumed(screen: S) {
        if (asked) {
            if (!done) {
                done = true
                answered(screen)
            }
            return
        }
        if (gone(screen)) return
        resumed.removeAll { it === screen }
        resumed += screen
        waitOnLast()
    }

    fun paused(screen: S) {
        if (asked || resumed.none { it === screen }) return
        resumed.removeAll { it === screen }
        waitOnLast()
    }

    /** Waits on the last resumed screen, dropping any earlier wait. */
    private fun waitOnLast() {
        cancel?.invoke()
        cancel = null
        val screen = resumed.lastOrNull() ?: return
        cancel =
            schedule(settleMs) {
                cancel = null
                if (asked || gone(screen)) return@schedule
                asked = true
                resumed.clear()
                ask(screen)
            }
    }
}
