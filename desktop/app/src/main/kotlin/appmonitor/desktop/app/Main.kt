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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

fun main() {
    val store = TrafficStore()
    var status by mutableStateOf("Starting server…")
    runBlocking {
        runUntilExit(serve = { runServer(AppMonitorServer(), store) { status = it } }) {
            awaitApplication {
                Window(
                    onCloseRequest = ::exitApplication,
                    title = "App Monitor",
                    state = rememberWindowState(width = 1280.dp, height = 800.dp),
                ) {
                    val state by store.state.collectAsState()
                    AppMonitorApp(state, status)
                }
            }
        }
    }
    exitProcess(0)
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

/**
 * Starts [server] and feeds [store] until cancelled; any startup, session or [stop] failure, [Error]s
 * included, is reported through [onStatus]. A [VirtualMachineError] is rethrown after it.
 */
internal suspend fun runServer(
    server: AppMonitorServer,
    store: TrafficStore,
    start: suspend () -> Int = server::start,
    stop: suspend () -> Unit = server::stop,
    onStatus: (String) -> Unit,
) {
    try {
        val port = start()
        onStatus("Listening on port $port")
        store.collect(server.sessions)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onStatus("Server failed: ${e.message ?: e::class.simpleName}")
        if (e is VirtualMachineError) throw e
    } finally {
        withContext(NonCancellable) {
            try {
                stop()
            } catch (e: Exception) {
                onStatus("Server stop failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }
}
