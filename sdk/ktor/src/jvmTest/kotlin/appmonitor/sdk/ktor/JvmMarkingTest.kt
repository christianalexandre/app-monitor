package appmonitor.sdk.ktor

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmMarkingTest {
    @Test
    fun capturedRequestsAreMarkedBesideTheOkHttpSdk() = assertEquals(true, MARKS_CAPTURED_REQUESTS)
}
