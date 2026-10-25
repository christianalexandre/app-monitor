package appmonitor.desktop.storage

import appmonitor.desktop.storage.db.Mock_rules
import appmonitor.protocol.Protocol
import appmonitor.v1.MockRule

/** Who created a mock rule: one from MCP stays pending until the window confirms it (ADR-0110 F.1, ADR-0005). */
enum class MockSource(
    internal val stored: String,
) {
    WINDOW("window"),
    MCP("mcp"),
    ;

    internal companion object {
        fun of(stored: String): MockSource = entries.first { it.stored == stored }
    }
}

/** A stored rule; [rule] carries [id], and a device of [appId] is sent it while [active] (ADR-0113). */
data class StoredMock(
    val id: Long,
    val appId: String,
    val position: Long,
    val name: String,
    val enabled: Boolean,
    val pending: Boolean,
    val source: MockSource,
    val rule: MockRule,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val active: Boolean get() = enabled && !pending
}

/** What [Storage.updateMock] changes; a null field keeps what is stored. [rule]'s id is ignored. */
data class MockChange(
    val name: String? = null,
    val enabled: Boolean? = null,
    val rule: MockRule? = null,
)

/** What a queued mock write did, known once its batch is committed or lost. */
sealed interface MockWrite {
    data class Saved(
        val id: Long,
    ) : MockWrite

    /** Nothing written: the rule was deleted, or never stored. */
    data object UnknownMock : MockWrite

    /** Its batch was lost, or the store was closing. */
    data object NotSaved : MockWrite
}

/** Throws for a rule no SDK can match or answer with (ADR-0110 B.1, C.1). */
internal fun requireValid(rule: MockRule) {
    require(rule.host.isNotBlank()) { "A mock rule needs a host" }
    require(rule.path.startsWith("/")) { "A mock rule's path starts with /" }
    require((rule.response == null) != (rule.failure == null)) { "A mock rule answers a response or a failure" }
    require(rule.delay_ms >= 0) { "A mock rule's delay is not negative" }
    rule.response?.let { require(it.body.size <= Protocol.MAX_BODY_BYTES) { "A mock body is at most ${Protocol.MAX_BODY_BYTES} bytes" } }
}

// The stored blob holds no id: the row's is the one sent.
internal fun MockRule.stored(): ByteArray = copy(id = 0).encode()

internal fun Mock_rules.toStored() =
    StoredMock(
        id = id,
        appId = app_id,
        position = position,
        name = name,
        enabled = enabled != 0L,
        pending = pending != 0L,
        source = MockSource.of(source),
        rule = MockRule.ADAPTER.decode(rule).copy(id = id),
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms,
    )
