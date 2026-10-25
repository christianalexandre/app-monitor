package appmonitor.desktop.mcp

import appmonitor.desktop.storage.MockSource
import appmonitor.desktop.storage.MockWrite
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StorageHealth
import appmonitor.desktop.storage.StoredMock
import appmonitor.protocol.Protocol
import appmonitor.v1.Header
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/** `create_mock` (SDD 10, ADR-0114 C.1): a rule that stays pending until the owner confirms it in the window (ADR-0005). */
internal object CreateMock {
    const val NAME = "create_mock"
    private const val MAX_HEADERS = 100

    val description =
        "Creates a mock rule for one app: a call whose method (any when omitted), host (ignoring case) and path (a * " +
            "segment matches any one segment, the query is not matched) match is answered on the device without the " +
            "network, by a response (status_code, headers, body) or a transport failure (failure_message), after " +
            "delay_ms. app_id is the app's id as list_runs shows it in sessions. The rule is pending: no device gets it " +
            "until the developer confirms it in the window's Mocks panel. It goes last in the app's match order; the " +
            "first matching rule answers. No tool edits, confirms or deletes a rule."

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putString("app_id", "The app the rule is for, as list_runs shows it.")
                    putString("name", "What the rule is for, shown in the window; not blank.")
                    putString("method", "The HTTP method, exact; omit for any.")
                    putString("host", "The URL's host, ignoring case.")
                    putString("path", "The URL's path, starting with /; a * segment matches any one segment.")
                    putJsonObject("delay_ms") {
                        put("type", "integer")
                        put("minimum", 0)
                        put("default", 0)
                        put("description", "Wait before answering.")
                    }
                    putJsonObject("status_code") {
                        put("type", "integer")
                        put("minimum", 100)
                        put("maximum", 599)
                        put("description", "Answer with a response of this status; or give failure_message.")
                    }
                    putJsonObject("headers") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string") }
                                putJsonObject("value") { put("type", "string") }
                            }
                            put("required", JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("value"))))
                        }
                        put("maxItems", MAX_HEADERS)
                        put("description", "The response's headers, in order.")
                    }
                    putString("body", "The response's body as UTF-8 text.")
                    putString("body_base64", "The response's body as base64, instead of body.")
                    putString("failure_message", "Fail the call as a transport error with this message, instead of a response.")
                },
            required = listOf("app_id", "name", "host", "path"),
        )

    suspend fun call(
        storage: Storage,
        arguments: JsonObject?,
        health: () -> StorageHealth,
    ): JsonObject {
        val appId = arguments.required("app_id")
        val name = arguments.required("name")
        val host = arguments.required("host").trim()
        val path = arguments.required("path")
        if (!path.startsWith("/")) throw ToolError("path must start with /")
        val status = arguments.long("status_code", 100L..599L, null)
        val failure = arguments.string("failure_message")
        if ((status == null) == (failure == null)) throw ToolError("give status_code or failure_message, not both")
        val response =
            status?.let { MockResponse(status_code = it.toInt(), headers = arguments.headers(), body = arguments.body()) }
        if (response == null && (arguments.has("headers") || arguments.has("body") || arguments.has("body_base64"))) {
            throw ToolError("headers and body go with status_code")
        }
        val rule =
            MockRule(
                method = arguments.string("method").orEmpty().trim(),
                host = host,
                path = path,
                delay_ms = arguments.long("delay_ms", 0L..Long.MAX_VALUE, 0)!!,
                response = response,
                failure = failure?.let(::MockFailure),
            )
        val write =
            try {
                storage.createMock(appId, name, rule, MockSource.MCP)
            } catch (e: IllegalArgumentException) {
                throw ToolError(e.message ?: "the rule is not valid")
            }
        val id =
            when (write) {
                is MockWrite.Saved -> write.id
                MockWrite.UnknownMock -> throw ToolError("the rule was not saved")
                MockWrite.NotSaved -> throw ToolError("the rule was not saved" + (health().lastError?.let { ": $it" } ?: ""))
            }
        // Deleted in the window between the write and this read.
        val stored = storage.mocks().firstOrNull { it.id == id } ?: throw ToolError("no mock rule with id $id")
        return buildJsonObject { put("mock", stored.toJson()) }
    }

    private fun JsonObject?.required(name: String): String =
        string(name)?.takeIf { it.isNotBlank() } ?: throw ToolError("$name is required and not blank")

    private fun JsonObject?.has(name: String) = this?.get(name)?.let { it !is JsonNull } == true

    private fun JsonObject?.headers(): List<Header> {
        val value = this?.get("headers")
        if (value == null || value is JsonNull) return emptyList()
        val items = value as? JsonArray
        if (items == null || items.size > MAX_HEADERS) throw ToolError("headers must be an array of at most $MAX_HEADERS objects")
        return items.map { item ->
            val header = item as? JsonObject
            val name = header.string("name")?.takeIf { it.isNotBlank() }
            val value = header.string("value")
            if (name == null || value == null) throw ToolError("each header needs a name and a value, as strings")
            Header(name, value)
        }
    }

    private fun JsonObject?.body(): ByteString {
        val text = string("body")
        val base64 = string("body_base64")
        if (text != null && base64 != null) throw ToolError("give body or body_base64, not both")
        val body =
            text?.encodeUtf8() ?: base64?.let { it.decodeBase64() ?: throw ToolError("body_base64 is not base64") } ?: ByteString.EMPTY
        if (body.size > Protocol.MAX_BODY_BYTES) throw ToolError("the body is over ${Protocol.MAX_BODY_BYTES} bytes")
        return body
    }

    private fun JsonObjectBuilder.putString(
        name: String,
        description: String,
    ) = putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

/** A stored rule as `create_mock` answers it. */
internal fun StoredMock.toJson() =
    buildJsonObject {
        put("id", id)
        put("app_id", appId)
        put("name", name)
        put("enabled", enabled)
        put("pending", pending)
        put("source", source.name.lowercase())
        put("method", rule.method.ifEmpty { null }.json())
        put("host", rule.host)
        put("path", rule.path)
        put("delay_ms", rule.delay_ms)
        put(
            "response",
            rule.response?.let { response ->
                buildJsonObject {
                    put("status_code", response.status_code)
                    put("headers", response.headers.toJson())
                    put("body_bytes", response.body.size)
                }
            } ?: JsonNull,
        )
        put("failure_message", rule.failure?.message.json())
        put("created_at", time(createdAtMs))
        put("updated_at", time(updatedAtMs))
    }
