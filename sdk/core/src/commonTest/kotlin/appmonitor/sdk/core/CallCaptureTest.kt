@file:OptIn(InternalAppMonitorApi::class)

package appmonitor.sdk.core

import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.io.IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ADR-0182: what both Kotlin captures decide on a call, once; each library's own tests read and rebuild real calls. */
class CallCaptureTest {
    private val sent = mutableListOf<Envelope>()
    private val asked = mutableListOf<BreakpointStage>()
    private val rule = BreakpointRule(id = 3, path = "/items", request = true, response = true, timeout_ms = 1_000)
    private val sink =
        object : CaptureSink {
            override val options = AppMonitorOptions()

            override fun send(envelope: Envelope) {
                sent += envelope
            }

            override fun breakpointFor(
                method: String,
                url: String,
                stage: BreakpointStage,
            ): BreakpointRule {
                asked += stage
                return rule
            }
        }
    private val capture = CallCapture(sink, "id-1")
    private val started = RequestStarted(request_id = "id-1", method = "GET", url = "https://a.example/items")
    private val received = ResponseReceived(request_id = "id-1", status_code = 200)

    private fun resume(
        status: Int? = null,
        method: String? = null,
    ) = BreakpointOutcome.Resume(BreakpointResume(request_id = "id-1", status_code = status, method = method))

    private fun failure(index: Int = sent.lastIndex) = RequestFailed.ADAPTER.decode(sent[index].payload)

    @Test
    fun aResumedRequestReachesItsEditWithNothingCaptured() {
        val resume = resume(method = "PUT")

        val edited = capture.holdRequest(rule, started, { hit -> resume.also { assertSame(started, hit.request) } }) { it }

        assertSame(resume, edited)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aHitCarriesTheRuleAndItsStage() {
        var hit: BreakpointHit? = null

        capture.holdRequest(rule, started, { h -> BreakpointOutcome.UNCHANGED.also { hit = h } }) { }
        assertEquals(BreakpointStage.BREAKPOINT_STAGE_REQUEST, hit?.stage)
        assertEquals(3L, hit?.rule_id)
        assertEquals(1_000L, hit?.timeout_ms)

        capture.holdResponse(rule, started, received, { h -> BreakpointOutcome.UNCHANGED.also { hit = h } }, { }) { it }
        assertEquals(BreakpointStage.BREAKPOINT_STAGE_RESPONSE, hit?.stage)
        assertSame(received, hit?.response)
    }

    @Test
    fun anAbortedRequestIsCapturedThenFails() {
        val error =
            assertFailsWith<IOException> {
                capture.holdRequest<Unit>(rule, started, { BreakpointOutcome.Abort("no") }) { }
            }

        assertEquals("no", error.message)
        assertEquals(listOf("RequestStarted", "RequestFailed"), sent.map { it.type })
        assertEquals("id-1", failure().request_id)
    }

    @Test
    fun anEditThrowingAnythingElseIsRefusedWithItsReason() {
        val error =
            assertFailsWith<IOException> {
                capture.holdRequest<Unit>(rule, started, { BreakpointOutcome.UNCHANGED }) { throw IllegalArgumentException("bad name") }
            }

        assertEquals("Breakpoint edit refused: bad name", error.message)
        assertEquals(listOf("RequestStarted", "RequestFailed"), sent.map { it.type })
    }

    @Test
    fun aHoldThatFailsOrIsCancelledCapturesTheRequestAndKeepsItsError() {
        val io = IOException("Canceled")
        assertSame(io, assertFailsWith<IOException> { capture.holdRequest<Unit>(rule, started, { throw io }) { } })
        assertEquals(listOf("RequestStarted", "RequestFailed"), sent.map { it.type })

        sent.clear()
        val cancelled = CancellationException("gone")
        assertSame(cancelled, assertFailsWith<CancellationException> { capture.holdRequest<Unit>(rule, started, { throw cancelled }) { } })
        assertEquals(listOf("RequestStarted", "RequestFailed"), sent.map { it.type })
    }

    @Test
    fun onlyAResumeChangingTheResponseIsHandedBack() {
        val hold = { outcome: BreakpointOutcome ->
            capture.holdResponse(rule, started, received, { outcome }, { error("not closed") }) { it }
        }

        assertNull(hold(BreakpointOutcome.UNCHANGED))
        assertNull(hold(resume(method = "PUT")), "no response field is set")
        assertNotNull(hold(resume(status = 500)))
        assertNotNull(hold(BreakpointOutcome.Resume(BreakpointResume(request_id = "id-1", removed_headers = listOf("X")))))
        assertNotNull(hold(BreakpointOutcome.Resume(BreakpointResume(request_id = "id-1", body = "b".encodeUtf8()))))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anAbortedOrFailedResponseClosesAndFailsWithoutARequestStarted() {
        var closed = 0

        assertFailsWith<IOException> {
            capture.holdResponse(
                rule,
                started,
                received,
                { BreakpointOutcome.Abort("no") },
                { closed++ },
            ) { it }
        }
        assertEquals(1, closed)
        assertEquals(listOf("RequestFailed"), sent.map { it.type })
        assertContains(failure().error, "no")

        sent.clear()
        assertFailsWith<IOException> {
            capture.holdResponse(
                rule,
                started,
                received,
                { throw IOException("Canceled") },
                { closed++ },
            ) { it }
        }
        assertEquals(2, closed)
        assertEquals(listOf("RequestFailed"), sent.map { it.type })

        sent.clear()
        assertFailsWith<CancellationException> {
            capture.holdResponse(rule, started, received, { throw CancellationException("gone") }, { closed++ }) { it }
        }
        assertEquals(3, closed)
        assertEquals(listOf("RequestFailed"), sent.map { it.type })
    }

    @Test
    fun aResponseEditThrowingIsRefusedClosesAndFails() {
        var closed = 0
        val hold = { thrown: Exception ->
            capture.holdResponse<Unit>(rule, started, received, { resume(status = 500) }, { closed++ }) { throw thrown }
        }

        val refused = assertFailsWith<IOException> { hold(IllegalArgumentException("bad name")) }
        assertEquals("Breakpoint edit refused: bad name", refused.message)
        assertEquals(1, closed)
        assertEquals(listOf("RequestFailed"), sent.map { it.type })
        assertContains(failure().error, "Breakpoint edit refused: bad name")

        sent.clear()
        val io = IOException("read failed")
        assertSame(io, assertFailsWith<IOException> { hold(io) })
        assertEquals(2, closed)
        assertEquals(listOf("RequestFailed"), sent.map { it.type })

        sent.clear()
        val cancelled = CancellationException("gone")
        assertSame(cancelled, assertFailsWith<CancellationException> { hold(cancelled) })
        assertEquals(3, closed)
        assertEquals(listOf("RequestFailed"), sent.map { it.type })
    }

    @Test
    fun anUpgradeIsNeverHeldAtItsResponse() {
        assertNull(capture.responseBreakpoint("GET", "https://a.example/items", 101))
        assertTrue(asked.isEmpty())

        assertSame(rule, capture.responseBreakpoint("GET", "https://a.example/items", 200))
        assertSame(rule, capture.requestBreakpoint("GET", "https://a.example/items"))
        assertEquals(listOf(BreakpointStage.BREAKPOINT_STAGE_RESPONSE, BreakpointStage.BREAKPOINT_STAGE_REQUEST), asked)
    }

    @Test
    fun aMockWaitsItsDelayThenAnswers() {
        val answer = MockResponse(status_code = 201)
        var waited = 0L

        assertSame(answer, capture.answer(MockRule(id = 1, delay_ms = 40, response = answer)) { waited = it })

        assertEquals(40, waited)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aMockFailureOrAFailedWaitFailsTheCall() {
        val failing = MockRule(id = 1, failure = MockFailure(message = "reset"))
        assertEquals("reset", assertFailsWith<IOException> { capture.answer(failing) { } }.message)
        assertContains(failure().error, "reset")

        val answering = MockRule(id = 1, response = MockResponse(status_code = 200))
        sent.clear()
        assertFailsWith<IOException> { capture.answer(answering) { throw IOException("Canceled") } }
        assertContains(failure().error, "Canceled")

        sent.clear()
        assertFailsWith<CancellationException> { capture.answer(answering) { throw CancellationException("gone") } }
        assertEquals(listOf("RequestFailed"), sent.map { it.type })
    }

    @Test
    fun whatARequestAndAResponseResumeChangeAreTheirOwnFields() {
        assertTrue(resume(method = "PUT").changesRequest)
        assertTrue(BreakpointOutcome.Resume(BreakpointResume(request_id = "i", url = "https://b.example/")).changesRequest)
        assertTrue(resume(status = 500).changesResponse)
        assertEquals(false, resume(status = 500).changesRequest)
        assertEquals(false, resume(method = "PUT").changesResponse)
        assertEquals(false, BreakpointOutcome.Resume(null).changesRequest)
    }
}
