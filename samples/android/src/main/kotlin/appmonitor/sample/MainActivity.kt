package appmonitor.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import appmonitor.sdk.core.AppMonitorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val calls = SampleCalls()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocalNetwork()
        intent.getStringExtra(EXTRA_HOST)?.let { host ->
            (application as SampleApplication).restartMonitor(host, intent.getIntExtra(EXTRA_PORT, AppMonitorOptions().port))
        }
        val runAll = savedInstanceState == null && intent.getBooleanExtra(EXTRA_RUN_ALL, false)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Sample(calls, runAll) } } }
    }
}

// Launch extras read by scripts/e2e_android.sh; renaming one breaks `make e2e-android`.
private const val EXTRA_HOST = "host"
private const val EXTRA_PORT = "port"
private const val EXTRA_RUN_ALL = "runAll"

private data class SampleButton(
    val label: String,
    val call: suspend () -> String,
)

@Composable
private fun Sample(
    calls: SampleCalls,
    runAll: Boolean,
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("Tap a button; the call shows up in the desktop.") }
    val buttons =
        listOf(
            SampleButton("GET JSON") { calls.getJson() },
            SampleButton("POST") { calls.postJson() },
            SampleButton("404") { calls.notFound() },
            SampleButton("Timeout") { calls.timeout() },
            SampleButton("WebSocket echo") { calls.webSocketEcho() },
        )
    LaunchedEffect(Unit) {
        if (!runAll) return@LaunchedEffect
        buttons.forEach { (label, call) -> result = "$label: ${withContext(Dispatchers.IO) { call() }}" }
    }
    Column(
        Modifier.safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons.forEach { (label, call) ->
            Button(
                onClick = {
                    result = "$label…"
                    scope.launch { result = "$label: ${withContext(Dispatchers.IO) { call() }}" }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(label) }
        }
        Text(result)
    }
}
