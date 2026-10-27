package appmonitor.sdk.ktor

/**
 * True where a second automatic capture can see Ktor's requests: the Swift SDK on iOS (ADR-0064),
 * `appmonitor-okhttp` on the JVM and Android (ADR-0129).
 */
internal expect val MARKS_CAPTURED_REQUESTS: Boolean
