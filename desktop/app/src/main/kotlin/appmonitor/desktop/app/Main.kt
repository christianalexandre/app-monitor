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
import appmonitor.desktop.app.ui.StorageUnavailable
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.STOP_TIMEOUT
import appmonitor.desktop.server.printStopFailure
import appmonitor.desktop.server.shutdownHook
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.dataDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Duration

fun main() {
    val directory = dataDirectory()
    val storage =
        try {
            Storage.open(directory)
        } catch (e: Exception) {
            exitAfter { runBlocking { showStorageFailure(directory.resolve(Storage.FILE_NAME).toString(), e) } }
            return
        }
    val store = TrafficStore(storage)
    var status by mutableStateOf("Starting server…")
    val approvals = DeviceApprovals()
    val server = AppMonitorServer(approval = approvals)
    val stopServer = once(server::stop)
    val closeStorage = once { storage.close(STOP_TIMEOUT) }
    addStopHook(timeout = STOP_TIMEOUT * 2) {
        try {
            stopServer()
        } finally {
            closeStorage()
        }
    }
    exitAfter {
        runBlocking {
            try {
                runUntilExit(serve = { runServer(server, store::collect, stop = stopServer) { status = it } }) {
                    awaitApplication {
                        Window(
                            onCloseRequest = ::exitApplication,
                            title = "App Monitor",
                            state = rememberWindowState(width = 1280.dp, height = 800.dp),
                        ) {
                            val pending by approvals.pending.collectAsState()
                            AppMonitorApp(storage, status, pending, approvals::decide)
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { closeStorage() }
            }
        }
    }
}

private suspend fun showStorageFailure(
    file: String,
    failure: Exception,
) {
    failure.printStackTrace()
    awaitApplication {
        Window(onCloseRequest = ::exitApplication, title = "App Monitor") {
            StorageUnavailable(file, failure.message ?: failure.toString())
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

/** Runs [stop] on SIGINT, SIGTERM or `exitProcess`, giving up after [timeout]; failures go to [onFailure]. */
internal fun addStopHook(
    addShutdownHook: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    onFailure: (Throwable) -> Unit = ::printStopFailure,
    timeout: Duration = STOP_TIMEOUT,
    stop: suspend () -> Unit,
) = addShutdownHook(shutdownHook(timeout, onFailure, stop))

/**
 * Feeds [collect] with [server]'s sessions until cancelled; reports through [onStatus] a stop by someone else, a [stop] past
 * [stopTimeout] and any failure, foreign [CancellationException]s included. Rethrows a [VirtualMachineError].
 */
internal suspend fun runServer(
    server: AppMonitorServer,
    collect: suspend (Flow<DeviceSession>) -> Unit,
    start: suspend () -> Int = server::start,
    stop: suspend () -> Unit = server::stop,
    stopTimeout: Duration = STOP_TIMEOUT,
    onStatus: (String) -> Unit,
) {
    try {
        val port = start()
        onStatus("Listening on port $port")
        collect(server.sessions)
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
