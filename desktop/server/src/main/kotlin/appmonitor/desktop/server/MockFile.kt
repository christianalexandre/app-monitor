package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.encodeUtf8

/**
 * The `--mocks` file: `{"rules": [...]}`, each rule `id`, `host`, `path`, optional `method` and `delay_ms`, and either
 * `response` (`status_code`, optional `headers` of `name` and `value`, optional UTF-8 `body`) or `failure`
 * (`message`), as `interaction.proto` names them. Throws [IllegalArgumentException] on anything else.
 */
internal fun readMockRules(text: String): MockRules {
    val rules =
        try {
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("rules")
                .jsonArray
                .map { mockRule(it.jsonObject) }
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a rules file: ${e.message}", e)
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("not a rules file: ${e.message}", e)
        }
    return MockRules(rules).also {
        require(it.encode().size < Protocol.MAX_FRAME_BYTES - 1_024) { "the rules do not fit in one frame" }
    }
}

/** [rules] as the desktop sends them (ADR-0113). */
internal fun mockRulesEnvelope(
    rules: MockRules,
    atMs: Long,
) = Envelope(channel = Protocol.CHANNEL_INTERACTION, type = "MockRules", payload = rules.encodeByteString(), timestamp_ms = atMs)

private fun mockRule(rule: JsonObject): MockRule {
    val response = rule["response"]?.jsonObject
    val failure = rule["failure"]?.jsonObject
    require((response == null) != (failure == null)) { "rule ${rule["id"]}: one of response or failure" }
    return MockRule(
        id = rule.getValue("id").jsonPrimitive.long,
        method = rule.string("method").orEmpty(),
        host = requireNotNull(rule.string("host")) { "rule ${rule["id"]}: no host" },
        path = requireNotNull(rule.string("path")) { "rule ${rule["id"]}: no path" },
        delay_ms = rule["delay_ms"]?.jsonPrimitive?.long ?: 0,
        response =
            response?.let {
                MockResponse(
                    status_code = it.getValue("status_code").jsonPrimitive.int,
                    headers =
                        it["headers"]?.jsonArray.orEmpty().map { header ->
                            val fields = header.jsonObject
                            Header(requireNotNull(fields.string("name")) { "a header with no name" }, fields.string("value").orEmpty())
                        },
                    body = it.string("body").orEmpty().encodeUtf8(),
                )
            },
        failure = failure?.let { MockFailure(it.string("message").orEmpty()) },
    )
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
