package appmonitor.sdk.ktor

import kotlin.test.Test
import kotlin.test.assertEquals

class IosMarkingTest {
    @Test
    fun capturedRequestsAreMarked() = assertEquals(true, MARKS_CAPTURED_REQUESTS)
}
