package appmonitor.sdk.ktor

import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.okhttp.LookalikeEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmMarkingTest {
    @Test
    fun capturedRequestsAreMarkedBesideTheOkHttpSdk() = assertEquals(true, MARKS_CAPTURED_REQUESTS)

    @Test
    fun onlyTheOkHttpEngineCarriesTheMarkToTheInterceptor() {
        assertEquals(true, passesAnotherCapture(OkHttp.create()))
        assertEquals(false, passesAnotherCapture(CIO.create()))
        assertEquals(false, passesAnotherCapture(MockEngine { respondOk() }))
    }

    @Test
    fun anEngineIsKnownByItsClassNotItsName() = assertEquals(false, passesAnotherCapture(LookalikeEngine()))
}
