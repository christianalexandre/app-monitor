package appmonitor.sdk.ktor

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttpEngine

// ADR-0129: AppMonitorInterceptor skips and strips the mark; without appmonitor-okhttp, nothing would.
internal actual val MARKS_CAPTURED_REQUESTS: Boolean =
    runCatching { Class.forName("appmonitor.sdk.okhttp.AppMonitorInterceptor") }.isSuccess

// Another engine never reaches AppMonitorInterceptor, so the mark would reach the server. By class, not name:
// R8 may rename it; the app may lack it (consumer-rules.pro). An engine wrapping it is not known (DEBT-0100).
internal actual fun passesAnotherCapture(engine: HttpClientEngine): Boolean = runCatching { engine is OkHttpEngine }.getOrDefault(false)
