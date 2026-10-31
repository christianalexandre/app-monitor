package appmonitor.protocol

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.WireEnum
import com.squareup.wire.WireField
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Random messages of every type, from a fixed seed, written by Wire to `fixtures/corpus/<Message>.bin` as
 * varint-length-prefixed records; the Swift SDK decodes and re-encodes each one byte for byte (ADR-0160).
 * Regenerate with the fixtures: ./gradlew :protocol:jvmTest -PupdateFixtures
 */
class CorpusTest {
    companion object {
        private val dir = File(System.getProperty("appmonitor.fixtures"), "corpus")
        private const val PER_TYPE = 25

        init {
            if (System.getProperty("appmonitor.updateFixtures") == "true") {
                dir.deleteRecursively()
                dir.mkdirs()
                MESSAGES.forEach { name -> File(dir, "$name.bin").writeBytes(records(corpus(name))) }
            }
        }

        private fun type(name: String): Class<*> = Class.forName("appmonitor.v1.$name")

        private fun adapter(name: String): ProtoAdapter<Any> {
            @Suppress("UNCHECKED_CAST")
            return type(name).getField("ADAPTER").get(null) as ProtoAdapter<Any>
        }

        /** [PER_TYPE] messages of [name], the same for every run. */
        fun corpus(name: String): List<ByteArray> {
            val random = Random(name.hashCode().toLong() * 31 + 2026)
            return List(PER_TYPE) { adapter(name).encode(RandomMessage(random).of(type(name), depth = 0)) }
        }

        private fun records(messages: List<ByteArray>): ByteArray {
            val buffer = Buffer()
            messages.forEach { bytes ->
                var length = bytes.size
                while (length >= 0x80) {
                    buffer.writeByte(length and 0x7F or 0x80)
                    length = length ushr 7
                }
                buffer.writeByte(length)
                buffer.write(bytes)
            }
            return buffer.readByteArray()
        }

        fun read(name: String): List<ByteArray> {
            val buffer = Buffer().write(File(dir, "$name.bin").readBytes())
            val out = mutableListOf<ByteArray>()
            while (!buffer.exhausted()) {
                var length = 0
                var shift = 0
                while (true) {
                    val byte = buffer.readByte().toInt()
                    length = length or (byte and 0x7F shl shift)
                    if (byte and 0x80 == 0) break
                    shift += 7
                }
                out += buffer.readByteArray(length.toLong())
            }
            return out
        }
    }

    @Test
    fun everyMessageHasACorpus() = assertEquals(MESSAGES.map { "$it.bin" }.toSet(), dir.list().orEmpty().toSet())

    @Test
    fun theCorpusIsWhatTheSeedGives() =
        MESSAGES.forEach { name ->
            val expected = corpus(name)
            val actual = read(name)
            assertEquals(expected.size, actual.size, name)
            expected.zip(actual).forEach { (e, a) -> assertContentEquals(e, a, name) }
        }

    @Test
    fun wireReencodesEveryRecordByteForByte() =
        MESSAGES.forEach { name ->
            read(name).forEach { bytes -> assertContentEquals(bytes, adapter(name).encode(adapter(name).decode(bytes)), name) }
        }
}

/** Every message of `protocol/proto`, by its Wire class name. */
private val MESSAGES =
    listOf(
        "Envelope",
        "Hello",
        "HelloAck",
        "DeliveryReport",
        "Header",
        "RequestStarted",
        "ResponseReceived",
        "RequestFailed",
        "WebSocketFrame",
        "MockRules",
        "MockRule",
        "MockResponse",
        "MockFailure",
        "BreakpointRules",
        "BreakpointRule",
        "BreakpointHit",
        "BreakpointResume",
        "BreakpointAbort",
        "ReplayRequest",
        "ReplayRefused",
    )

/** Builds a Wire message from its `@WireField`s: defaults, absent optionals, one member per oneof, nested messages. */
private class RandomMessage(
    private val random: Random,
) {
    fun of(
        type: Class<*>,
        depth: Int,
    ): Message<*, *> {
        val fields =
            type.declaredFields
                .mapNotNull { f ->
                    f.getAnnotation(WireField::class.java)?.let { f to it }
                }.sortedBy { it.second.schemaIndex }
        val oneofs = fields.map { it.second }.filter { it.oneofName.isNotEmpty() }.groupBy { it.oneofName }
        val chosen = oneofs.mapValues { (_, members) -> members.randomOrNull(random)?.takeIf { random.nextInt(4) > 0 } }
        val values =
            fields.map { (field, wire) ->
                when {
                    wire.oneofName.isNotEmpty() -> {
                        if (chosen[wire.oneofName] === wire) value(wire.adapter, depth) else null
                    }

                    wire.label == WireField.Label.REPEATED -> {
                        List(if (depth > 2) 0 else random.nextInt(4)) { value(wire.adapter, depth) }
                    }

                    wire.label == WireField.Label.OPTIONAL || field.type.isNullableMessage() -> {
                        if (random.nextBoolean() && depth <= 2) value(wire.adapter, depth) else null
                    }

                    random.nextInt(4) == 0 -> {
                        identity(wire.adapter)
                    }

                    else -> {
                        value(wire.adapter, depth)
                    }
                }
            }
        val constructor = type.constructors.single { it.parameterCount == fields.size + 1 }
        return constructor.newInstance(*values.toTypedArray(), ByteString.EMPTY) as Message<*, *>
    }

    private fun Class<*>.isNullableMessage() = Message::class.java.isAssignableFrom(this)

    private fun identity(adapter: String): Any =
        when (adapter.substringAfter('#')) {
            "STRING" -> ""
            "BYTES" -> ByteString.EMPTY
            "BOOL" -> false
            "INT32", "UINT32" -> 0
            "INT64" -> 0L
            else -> enumValues(adapter).first { (it as WireEnum).value == 0 }
        }

    private fun value(
        adapter: String,
        depth: Int,
    ): Any =
        when (adapter.substringAfter('#')) {
            "STRING" -> {
                string()
            }

            "BYTES" -> {
                random.nextBytes(random.nextInt(0, 40)).toByteString()
            }

            "BOOL" -> {
                random.nextBoolean()
            }

            "INT32" -> {
                listOf(random.nextInt(), -1, Int.MIN_VALUE, Int.MAX_VALUE, random.nextInt(-300, 300)).random(random)
            }

            "UINT32" -> {
                random.nextInt()
            }

            "INT64" -> {
                listOf(random.nextLong(), -1L, Long.MIN_VALUE, Long.MAX_VALUE, random.nextLong(-300, 300)).random(random)
            }

            else -> {
                val type = Class.forName(adapter.substringBefore('#'))
                if (WireEnum::class.java.isAssignableFrom(type)) enumValues(adapter).random(random) else of(type, depth + 1)
            }
        }

    private fun enumValues(adapter: String): List<Any> = Class.forName(adapter.substringBefore('#')).enumConstants.toList()

    // ASCII, accents, CJK and an emoji: one to four UTF-8 bytes per code point.
    private fun string(): String =
        buildString {
            repeat(random.nextInt(0, 12)) { append(listOf("a", "Z", "/", " ", "é", "ç", "日", "本", "😀", "\u0000").random(random)) }
        }
}
