package appmonitor.sample.kmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class SampleButton(
    val label: String,
    val call: suspend () -> String,
)

/** The sample's one screen on both platforms; [runAll] taps every button in turn, as the end to ends do. */
@Composable
fun App(runAll: Boolean) {
    val calls = remember { SampleCalls() }
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
        buttons.forEach { (label, call) -> result = "$label: ${call()}" }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                buttons.forEach { (label, call) ->
                    Button(
                        onClick = {
                            result = "$label…"
                            scope.launch { result = "$label: ${call()}" }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
                Text(result)
            }
        }
    }
}
