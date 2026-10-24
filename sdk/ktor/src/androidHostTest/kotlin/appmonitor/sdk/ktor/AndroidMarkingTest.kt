package appmonitor.sdk.ktor

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidMarkingTest {
    @Test
    fun capturedRequestsAreNotMarked() = assertEquals(false, MARKS_CAPTURED_REQUESTS)
}
