package appmonitor.sdk.ktor

import io.ktor.client.engine.HttpClientEngine

/**
 * True where a second automatic capture can see Ktor's requests: the Swift SDK on iOS (ADR-0064),
 * `appmonitor-okhttp` on the JVM and Android (ADR-0129).
 */
internal expect val MARKS_CAPTURED_REQUESTS: Boolean

/** Whether [engine] hands the request to that capture: on the JVM and Android only Ktor's OkHttp engine does. */
internal expect fun passesAnotherCapture(engine: HttpClientEngine): Boolean
