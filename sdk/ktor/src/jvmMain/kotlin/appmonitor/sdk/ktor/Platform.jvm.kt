package appmonitor.sdk.ktor

import io.ktor.client.engine.HttpClientEngine

// ADR-0129: AppMonitorInterceptor skips and strips the mark; without appmonitor-okhttp, nothing would.
internal actual val MARKS_CAPTURED_REQUESTS: Boolean =
    runCatching { Class.forName("appmonitor.sdk.okhttp.AppMonitorInterceptor") }.isSuccess

// Another engine never reaches AppMonitorInterceptor, so the mark would reach the server.
internal actual fun passesAnotherCapture(engine: HttpClientEngine): Boolean =
    engine.javaClass.name.startsWith("io.ktor.client.engine.okhttp.")
