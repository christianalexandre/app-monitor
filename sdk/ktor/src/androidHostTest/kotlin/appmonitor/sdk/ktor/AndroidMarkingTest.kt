package appmonitor.sdk.ktor

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidMarkingTest {
    // The Android host tests run without appmonitor-okhttp; the JVM ones with it (ADR-0129).
    @Test
    fun capturedRequestsAreNotMarkedWithoutTheOkHttpSdk() = assertEquals(false, MARKS_CAPTURED_REQUESTS)
}
