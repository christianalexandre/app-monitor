package appmonitor.sdk.ktor

// ADR-0129: AppMonitorInterceptor skips and strips the mark; without appmonitor-okhttp, nothing would.
internal actual val MARKS_CAPTURED_REQUESTS: Boolean =
    runCatching { Class.forName("appmonitor.sdk.okhttp.AppMonitorInterceptor") }.isSuccess
