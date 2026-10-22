package appmonitor.protocol

import appmonitor.v1.Envelope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// Regenerate after a deliberate contract change: ./gradlew :protocol:jvmTest -PupdateFixtures
class FixtureTest {
    // Runs once per JVM: a per-instance init would rewrite the files before every test.
    companion object {
        private val dir = File(System.getProperty("appmonitor.fixtures"))

        init {
            if (System.getProperty("appmonitor.updateFixtures") == "true") {
                dir.listFiles { file -> file.extension == "bin" }.orEmpty().forEach { it.delete() }
                Samples.envelopes.forEach { (name, envelope) -> File(dir, name).writeBytes(envelope.encode()) }
            }
        }
    }

    @Test
    fun fixturesDecodeToSamples() {
        Samples.envelopes.forEach { (name, expected) ->
            assertEquals(expected, Envelope.ADAPTER.decode(File(dir, name).readBytes()), name)
        }
    }

    @Test
    fun samplesEncodeToFixtureBytes() {
        Samples.envelopes.forEach { (name, envelope) ->
            assertContentEquals(File(dir, name).readBytes(), envelope.encode(), name)
        }
    }

    @Test
    fun everyFixtureHasASample() {
        val files =
            dir
                .listFiles { file -> file.extension == "bin" }
                .orEmpty()
                .map { it.name }
                .toSet()
        assertEquals(Samples.envelopes.keys, files)
    }
}
