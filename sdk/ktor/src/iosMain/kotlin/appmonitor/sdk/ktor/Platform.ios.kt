package appmonitor.sdk.ktor

import io.ktor.client.engine.HttpClientEngine

internal actual val MARKS_CAPTURED_REQUESTS: Boolean = true

// The Swift SDK sees every URLSession task, the Darwin engine's (ADR-0064).
internal actual fun passesAnotherCapture(engine: HttpClientEngine): Boolean = true
