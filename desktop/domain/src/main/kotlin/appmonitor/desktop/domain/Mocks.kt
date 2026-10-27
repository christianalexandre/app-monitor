package appmonitor.desktop.domain

import appmonitor.protocol.Protocol
import appmonitor.v1.MockRule
import com.squareup.wire.ProtoAdapter
import kotlinx.coroutines.flow.Flow

/** Who created a mock rule: one from MCP stays pending until the window confirms it (ADR-0110 F.1, ADR-0005). */
enum class MockSource {
    WINDOW,
    MCP,
}

/** A stored rule; [rule] carries [id], and a device of [appId] is sent it while [active] (ADR-0113). */
data class Mock(
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

/** What [MockRepository.update] changes; a null field keeps what is stored. [rule]'s id is ignored. */
data class MockChange(
    val name: String? = null,
    val enabled: Boolean? = null,
    val rule: MockRule? = null,
)

/** What a mock write did, known once its batch is committed or lost. */
sealed interface MockWrite {
    data class Saved(
        val id: Long,
    ) : MockWrite

    /** Nothing written: the rule was deleted, or never stored. */
    data object UnknownMock : MockWrite

    /** Its batch was lost, or the store was closing. */
    data object NotSaved : MockWrite
}

/** Every app's mock rules, in match order (ADR-0113). */
interface MockRepository {
    /** Every rule, again after each change. */
    fun observeMocks(): Flow<List<Mock>>

    /** Every rule, by app id, then in match order; empty once the store is closing. */
    suspend fun mocks(): List<Mock>

    /** A rule for [appId], last in its match order; one from [MockSource.MCP] starts pending. Throws for an invalid rule. */
    suspend fun create(
        appId: String,
        name: String,
        rule: MockRule,
        source: MockSource,
    ): MockWrite

    /** Throws for an invalid rule. */
    suspend fun update(
        id: Long,
        change: MockChange,
    ): MockWrite

    /** The owner's confirmation of a pending rule (ADR-0005); a rule not pending stays as it is. */
    suspend fun confirm(id: Long): MockWrite

    suspend fun delete(id: Long)
}

/** How many of [rules], from the first, one `MockRules` envelope holds (ADR-0113 C.1); the window marks the others. */
fun fittingRules(rules: List<MockRule>): Int = fitting(rules, MockRule.ADAPTER)

/** How many of [rules], from the first, fit one envelope as field 1 of a rules message. */
fun <T> fitting(
    rules: List<T>,
    adapter: ProtoAdapter<T>,
): Int {
    var size = 0L
    return rules
        .takeWhile { rule ->
            size += adapter.encodedSizeWithTag(1, rule)
            size <= MAX_RULES_BYTES
        }.size
}

// Room for the envelope's own fields and the payload's length.
private const val MAX_RULES_BYTES = Protocol.MAX_FRAME_BYTES - 1_024
