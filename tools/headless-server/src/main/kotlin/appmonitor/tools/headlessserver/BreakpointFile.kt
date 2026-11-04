package appmonitor.tools.headlessserver

import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointRules
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException

/** The `--breakpoints` file: the rules a device gets, and the status each rule's response resume sets (ADR-0188). */
internal data class Breakpoints(
    val rules: BreakpointRules,
    val resumeStatus: Map<Long, Int>,
)

/**
 * `{"rules": [...]}`, each rule's fields as `interaction.proto` names them, plus `resume_status_code`, the
 * status a resume of its response sets. Throws [IllegalArgumentException] on anything else.
 */
internal fun readBreakpoints(text: String): Breakpoints {
    val rules =
        try {
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("rules")
                .jsonArray
                .map { it.jsonObject }
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a breakpoints file: ${e.message}", e)
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("not a breakpoints file: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("not a breakpoints file: ${e.message}", e)
        }
    val parsed = rules.map { breakpointRule(it) }
    val resumeStatus =
        rules
            .mapNotNull { rule ->
                rule["resume_status_code"]?.let { status ->
                    val code = requireNotNull(status.jsonPrimitive.intOrNull) { "rule ${rule["id"]}: resume_status_code not a number" }
                    require(code in MIN_STATUS..MAX_STATUS) { "rule ${rule["id"]}: resume_status_code $code" }
                    rule.getValue("id").jsonPrimitive.long to code
                }
            }.toMap()
    return Breakpoints(BreakpointRules(parsed), resumeStatus).also {
        require(it.rules.encode().size < Protocol.MAX_FRAME_BYTES - 1_024) { "the breakpoints do not fit in one frame" }
    }
}

/** The rules as the desktop sends them (SDD 12). */
internal fun breakpointRulesEnvelope(
    rules: BreakpointRules,
    atMs: Long,
) = Envelope(
    channel = Protocol.CHANNEL_INTERACTION,
    type = "BreakpointRules",
    payload = rules.encodeByteString(),
    timestamp_ms = atMs,
)

/**
 * The resume of [envelope] when it is a `BreakpointHit`, else null: the request unchanged, the response with its
 * rule's `resume_status_code` when it has one.
 */
internal fun breakpointResume(
    envelope: Envelope,
    breakpoints: Breakpoints,
): BreakpointResume? {
    if (envelope.channel != Protocol.CHANNEL_INTERACTION || envelope.type != "BreakpointHit") return null
    val hit =
        try {
            BreakpointHit.ADAPTER.decode(envelope.payload)
        } catch (_: IOException) {
            return null
        }
    val requestId = hit.request?.request_id ?: return null
    return when (hit.stage) {
        BreakpointStage.BREAKPOINT_STAGE_REQUEST -> {
            BreakpointResume(request_id = requestId, stage = hit.stage)
        }

        BreakpointStage.BREAKPOINT_STAGE_RESPONSE -> {
            BreakpointResume(request_id = requestId, stage = hit.stage, status_code = breakpoints.resumeStatus[hit.rule_id])
        }

        else -> {
            null
        }
    }
}

internal fun breakpointResumeEnvelope(
    resume: BreakpointResume,
    atMs: Long,
) = Envelope(
    channel = Protocol.CHANNEL_INTERACTION,
    type = "BreakpointResume",
    payload = resume.encodeByteString(),
    timestamp_ms = atMs,
)

/** The end to end scripts match this line: `resumed <request id> <stage>`, then ` status <code>` when set. */
internal fun describe(resume: BreakpointResume): String =
    "resumed ${resume.request_id} ${resume.stage}" + (resume.status_code?.let { " status $it" } ?: "")

private fun breakpointRule(rule: JsonObject): BreakpointRule {
    val request = rule.flag("request")
    val response = rule.flag("response")
    require(request || response) { "rule ${rule["id"]}: holds neither the request nor the response" }
    val timeout = requireNotNull(rule["timeout_ms"]?.jsonPrimitive?.long) { "rule ${rule["id"]}: no timeout_ms" }
    require(timeout >= 1) { "rule ${rule["id"]}: timeout_ms $timeout" }
    return BreakpointRule(
        id = rule.getValue("id").jsonPrimitive.long,
        method = rule.string("method").orEmpty(),
        host = requireNotNull(rule.string("host")) { "rule ${rule["id"]}: no host" },
        path = requireNotNull(rule.string("path")) { "rule ${rule["id"]}: no path" },
        request = request,
        response = response,
        timeout_ms = timeout,
    )
}

private fun JsonObject.flag(key: String): Boolean =
    get(key)?.let { requireNotNull(it.jsonPrimitive.booleanOrNull) { "rule ${get("id")}: $key not true or false" } } ?: false

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val MIN_STATUS = 100
private const val MAX_STATUS = 599
