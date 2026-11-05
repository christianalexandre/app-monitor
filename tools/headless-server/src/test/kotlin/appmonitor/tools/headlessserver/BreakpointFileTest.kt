package appmonitor.tools.headlessserver

import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.RequestStarted
import okio.ByteString.Companion.encodeUtf8
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BreakpointFileTest {
    @Test
    fun `the breakpoints file is read with the other options`() {
        val err = ByteArrayOutputStream()
        assertEquals(
            ServerArgs(0, breakpointsFile = Path.of("holds.json")),
            parseArgs(arrayOf("--breakpoints", "holds.json", "--port", "0"), PrintStream(err)),
        )
        assertNull(parseArgs(arrayOf("--breakpoints"), PrintStream(err)))
    }

    @Test
    fun `a breakpoints file becomes the rules in file order and each response's resume status`() {
        val breakpoints =
            readBreakpoints(
                """
                {"rules": [
                  {"id": 2, "method": "PUT", "host": "127.0.0.1", "path": "/put", "request": true, "response": true,
                   "timeout_ms": 600000, "resume_status_code": 299},
                  {"id": 5, "host": "10.0.2.2", "path": "/v1/*", "request": true, "timeout_ms": 30000}
                ]}
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                BreakpointRule(2, "PUT", "127.0.0.1", "/put", request = true, response = true, timeout_ms = 600_000),
                BreakpointRule(5, "", "10.0.2.2", "/v1/*", request = true, response = false, timeout_ms = 30_000),
            ),
            breakpoints.rules.rules,
        )
        assertEquals(mapOf(2L to 299), breakpoints.resumeStatus)
    }

    @Test
    fun `a bad breakpoints file is refused`() {
        listOf(
            "not json",
            "{}",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "timeout_ms": 1}]}""",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "request": "yes", "timeout_ms": 1}]}""",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "request": true}]}""",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "request": true, "timeout_ms": 0}]}""",
            """{"rules": [{"id": 1, "path": "/", "request": true, "timeout_ms": 1}]}""",
            """{"rules": [{"id": "x", "host": "h", "path": "/", "request": true, "timeout_ms": 1}]}""",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "response": true, "timeout_ms": 1, "resume_status_code": 99}]}""",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "response": true, "timeout_ms": 1, "resume_status_code": "x"}]}""",
        ).forEach { text -> assertFailsWith<IllegalArgumentException>(text) { readBreakpoints(text) } }
    }

    @Test
    fun `a request hit is resumed unchanged, a response one with its rule's status`() {
        val breakpoints =
            readBreakpoints(
                """{"rules": [{"id": 2, "host": "h", "path": "/put", "request": true, "response": true, "timeout_ms": 1,
               "resume_status_code": 299}, {"id": 3, "host": "h", "path": "/x", "response": true, "timeout_ms": 1}]}""",
            )
        val request = hit(2, BreakpointStage.BREAKPOINT_STAGE_REQUEST)
        assertEquals(BreakpointResume("r1", BreakpointStage.BREAKPOINT_STAGE_REQUEST), breakpointResume(request, breakpoints))
        assertEquals(
            BreakpointResume("r1", BreakpointStage.BREAKPOINT_STAGE_RESPONSE, status_code = 299),
            breakpointResume(hit(2, BreakpointStage.BREAKPOINT_STAGE_RESPONSE), breakpoints),
        )
        assertEquals(
            BreakpointResume("r1", BreakpointStage.BREAKPOINT_STAGE_RESPONSE),
            breakpointResume(hit(3, BreakpointStage.BREAKPOINT_STAGE_RESPONSE), breakpoints),
        )
        assertNull(breakpointResume(hit(2, BreakpointStage.BREAKPOINT_STAGE_UNSPECIFIED), breakpoints))
        assertNull(breakpointResume(request.copy(payload = "not a hit".encodeUtf8()), breakpoints))
        assertNull(breakpointResume(request.copy(type = "MockRules"), breakpoints))
        assertEquals(
            "interaction/BreakpointResume",
            breakpointResumeEnvelope(BreakpointResume("r1"), 0).run { "$channel/$type" },
        )
        assertEquals("interaction/BreakpointRules", breakpointRulesEnvelope(breakpoints.rules, 0).run { "$channel/$type" })
    }

    // The end to end scripts match these lines.
    @Test
    fun `a hit and its resume are logged with the request id and stage`() {
        assertEquals(
            "interaction/BreakpointHit r1 BREAKPOINT_STAGE_REQUEST rule 2",
            describe(hit(2, BreakpointStage.BREAKPOINT_STAGE_REQUEST)),
        )
        assertEquals(
            "resumed r1 BREAKPOINT_STAGE_RESPONSE status 299",
            describe(BreakpointResume("r1", BreakpointStage.BREAKPOINT_STAGE_RESPONSE, status_code = 299)),
        )
        assertEquals("resumed r1 BREAKPOINT_STAGE_REQUEST", describe(BreakpointResume("r1", BreakpointStage.BREAKPOINT_STAGE_REQUEST)))
    }

    private fun hit(
        ruleId: Long,
        stage: BreakpointStage,
    ) = Envelope(
        channel = Protocol.CHANNEL_INTERACTION,
        type = "BreakpointHit",
        payload =
            BreakpointHit(
                rule_id = ruleId,
                stage = stage,
                request = RequestStarted(request_id = "r1", method = "PUT", url = "http://h/put"),
            ).encodeByteString(),
    )
}
