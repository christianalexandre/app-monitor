package appmonitor.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.server.AppMonitorServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun main() =
    application {
        val store = remember { TrafficStore() }
        var status by remember { mutableStateOf("Starting server…") }
        LaunchedEffect(Unit) { runServer(AppMonitorServer(), store) { status = it } }
        Window(onCloseRequest = ::exitApplication, title = "App Monitor", state = rememberWindowState(width = 1280.dp, height = 800.dp)) {
            val state by store.state.collectAsState()
            AppMonitorApp(state, status)
        }
    }

/** Starts [server] and feeds [store] until cancelled; any startup or session failure is reported through [onStatus]. */
internal suspend fun runServer(
    server: AppMonitorServer,
    store: TrafficStore,
    onStatus: (String) -> Unit,
) {
    try {
        val port = server.start()
        onStatus("Listening on port $port")
        store.collect(server.sessions)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onStatus("Server failed: ${e.message ?: e::class.simpleName}")
    } finally {
        withContext(NonCancellable) { server.stop() }
    }
}
