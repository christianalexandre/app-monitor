package appmonitor.sample.kmp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import appmonitor.sdk.core.AppMonitorOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocalNetwork()
        intent.getStringExtra(EXTRA_HOST)?.let { host ->
            (application as SampleApplication).applyLaunchExtras(host, intent.getIntExtra(EXTRA_PORT, AppMonitorOptions().port))
        }
        val runAll = savedInstanceState == null && intent.getBooleanExtra(EXTRA_RUN_ALL, false)
        setContent { App(runAll, intent.getStringExtra(EXTRA_TARGET)) }
    }
}

// The launch extras of samples/android (ADR-0056); `make e2e-kmp` sends the same ones.
private const val EXTRA_HOST = "host"
private const val EXTRA_PORT = "port"
private const val EXTRA_RUN_ALL = "runAll"
private const val EXTRA_TARGET = "target"
