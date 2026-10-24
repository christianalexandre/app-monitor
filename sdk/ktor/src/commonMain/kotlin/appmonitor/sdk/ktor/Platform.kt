package appmonitor.sdk.ktor

/** True where a second automatic capture can see Ktor's requests: the Swift SDK on iOS (ADR-0064). */
internal expect val MARKS_CAPTURED_REQUESTS: Boolean
