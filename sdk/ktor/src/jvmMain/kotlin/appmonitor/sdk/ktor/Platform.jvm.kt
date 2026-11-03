package appmonitor.sdk.ktor

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttpConfig

// ADR-0129: AppMonitorInterceptor skips and strips the mark; without appmonitor-okhttp, nothing would.
internal actual val MARKS_CAPTURED_REQUESTS: Boolean =
    runCatching { Class.forName("appmonitor.sdk.okhttp.AppMonitorInterceptor") }.isSuccess

// ADR-0183: another engine never reaches AppMonitorInterceptor, so the mark would reach the server. A wrapper answers
// with the OkHttp config it delegates to; by class, not name: R8 may rename it, the app may lack it (consumer-rules.pro).
internal actual fun passesAnotherCapture(engine: HttpClientEngine): Boolean =
    runCatching { engine.config is OkHttpConfig }.getOrDefault(false)
