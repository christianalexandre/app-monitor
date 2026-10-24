package appmonitor.sdk.ktor

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmMarkingTest {
    @Test
    fun capturedRequestsAreNotMarked() = assertEquals(false, MARKS_CAPTURED_REQUESTS)
}
