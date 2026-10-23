package appmonitor.sdk.noop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR-0026: the no-op artifact's reference dumps declare exactly the real SDK's public API. */
class ApiMatchTest {
    private val sdkDir = File(System.getProperty("appmonitor.sdkDir"))

    @Test
    fun `no-op API matches the real SDK on every target`() {
        for (target in listOf("android", "jvm")) {
            val real = apiOf(target, "core", "android-okhttp")
            assertTrue(real.isNotEmpty(), "no $target dump in sdk/core or sdk/android-okhttp")
            assertEquals(real, apiOf(target, "no-op"), "$target API differs; run updateKotlinAbi and align sdk/no-op")
        }
    }

    @Test
    fun `a public member gained by either side is a difference`() {
        val real = apiOf("android", "core", "android-okhttp")
        val gained = parseDump("public final class appmonitor/sdk/core/AppMonitor {\n\tpublic static final fun pause ()V\n}")
        assertNotEquals(real, real + gained)
        val noOp = apiOf("android", "no-op")
        assertNotEquals(real, noOp + parseDump("public final class appmonitor/sdk/Extra {\n}"))
    }

    private fun apiOf(
        target: String,
        vararg modules: String,
    ): Set<String> =
        modules
            .flatMap { module -> parseDump(sdkDir.resolve("$module/api/$target/$module.api").readText()) }
            .toSet()
}

/** One entry per declaration line, prefixed by its class header, so a member moved between classes differs. */
internal fun parseDump(dump: String): Set<String> {
    val entries = mutableSetOf<String>()
    var header = ""
    for (line in dump.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
        when {
            line == "}" -> header = ""
            line.endsWith("{") -> header = line.removeSuffix("{").trim().also { entries += it }
            else -> entries += "$header :: $line"
        }
    }
    return entries
}
