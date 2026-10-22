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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

fun main() =
    application {
        val store = remember { TrafficStore() }
        var status by remember { mutableStateOf("Starting server…") }
        LaunchedEffect(Unit) {
            val server = AppMonitorServer()
            try {
                val port = server.start()
                status = "Listening on port $port"
                store.collect(server.sessions)
            } catch (e: IOException) {
                status = "Server failed: ${e.message}"
            } finally {
                withContext(NonCancellable) { server.stop() }
            }
        }
        Window(onCloseRequest = ::exitApplication, title = "App Monitor", state = rememberWindowState(width = 1280.dp, height = 800.dp)) {
            val state by store.state.collectAsState()
            AppMonitorApp(state, status)
        }
    }
