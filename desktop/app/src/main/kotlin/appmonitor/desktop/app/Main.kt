package appmonitor.desktop.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import androidx.compose.ui.window.rememberWindowState
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.STOP_TIMEOUT
import appmonitor.desktop.server.printStopFailure
import appmonitor.desktop.server.shutdownHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Duration

fun main() {
    val store = TrafficStore()
    var status by mutableStateOf("Starting server…")
    val approvals = DeviceApprovals()
    val server = AppMonitorServer(approval = approvals)
    val stop = once(server::stop)
    addStopHook(stop)
    exitAfter {
        runBlocking {
            runUntilExit(serve = { runServer(server, store, stop = stop) { status = it } }) {
                awaitApplication {
                    Window(
                        onCloseRequest = ::exitApplication,
                        title = "App Monitor",
                        state = rememberWindowState(width = 1280.dp, height = 800.dp),
                    ) {
                        val state by store.state.collectAsState()
                        val pending by approvals.pending.collectAsState()
                        AppMonitorApp(state, status, pending, approvals::decide)
                    }
                }
            }
        }
    }
}

/** Runs [block], then calls [exit] with 0, or with 1 after printing the failure when [block] throws. */
internal fun exitAfter(
    exit: (Int) -> Unit = ::exitProcess,
    block: () -> Unit,
) {
    val code =
        try {
            block()
            0
        } catch (e: Throwable) {
            e.printStackTrace()
            1
        }
    exit(code)
}

/** Runs [serve] while [ui] runs; once [ui] returns or fails, cancels [serve] and waits for it, its cleanup included. */
internal suspend fun runUntilExit(
    serve: suspend () -> Unit,
    ui: suspend () -> Unit,
) = coroutineScope {
    val serving = launch(Dispatchers.Default) { serve() }
    try {
        ui()
    } finally {
        withContext(NonCancellable) { serving.cancelAndJoin() }
    }
}

/** [stop] run by the first caller only; later calls return at once, so exit waits for one [STOP_TIMEOUT] at most. */
internal fun once(stop: suspend () -> Unit): suspend () -> Unit {
    val claimed = AtomicBoolean(false)
    return { if (claimed.compareAndSet(false, true)) stop() }
}

/** Runs [stop] on SIGINT, SIGTERM or `exitProcess`, giving up after [STOP_TIMEOUT]; failures go to [onFailure]. */
internal fun addStopHook(
    stop: suspend () -> Unit,
    addShutdownHook: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    onFailure: (Throwable) -> Unit = ::printStopFailure,
) = addShutdownHook(shutdownHook(onFailure = onFailure, stop = stop))

/**
 * Feeds [store] from [server] until cancelled; reports through [onStatus] a stop by someone else, a [stop] past
 * [stopTimeout] and any failure, foreign [CancellationException]s included. Rethrows a [VirtualMachineError].
 */
internal suspend fun runServer(
    server: AppMonitorServer,
    store: TrafficStore,
    start: suspend () -> Int = server::start,
    stop: suspend () -> Unit = server::stop,
    stopTimeout: Duration = STOP_TIMEOUT,
    onStatus: (String) -> Unit,
) {
    try {
        val port = start()
        onStatus("Listening on port $port")
        store.collect(server.sessions)
        onStatus("Server stopped")
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        onStatus("Server failed: ${e.message ?: e::class.simpleName}")
    } catch (e: Throwable) {
        onStatus("Server failed: ${e.message ?: e::class.simpleName}")
        if (e is VirtualMachineError) throw e
    } finally {
        withContext(NonCancellable) {
            try {
                withTimeout(stopTimeout) { stop() }
            } catch (e: TimeoutCancellationException) {
                onStatus("Server stop timed out after $stopTimeout")
            } catch (e: Throwable) {
                onStatus("Server stop failed: ${e.message ?: e::class.simpleName}")
                if (e is VirtualMachineError) throw e
            }
        }
    }
}
