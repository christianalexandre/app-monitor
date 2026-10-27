package appmonitor.sdk.noop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR-0072: each no-op module's reference dumps declare exactly its real module's public API. */
class ApiMatchTest {
    private val sdkDir = File(System.getProperty("appmonitor.sdkDir"))

    /** No-op module to the real module it mirrors, and the dumps both must have. */
    private val pairs =
        listOf(
            Triple("core-no-op", "core", listOf("android", "jvm", KLIB)),
            Triple("okhttp-no-op", "android-okhttp", listOf("android", "jvm")),
            Triple("ktor-no-op", "ktor", listOf("android", "jvm", KLIB)),
        )

    @Test
    fun `every no-op API matches its real module on every target`() {
        for ((noOp, real, targets) in pairs) {
            for (target in targets) {
                val expected = apiOf(real, target)
                assertTrue(expected.isNotEmpty(), "no $target dump in sdk/$real")
                assertEquals(expected, apiOf(noOp, target), "$target API differs; run updateKotlinAbi and align sdk/$noOp")
            }
        }
    }

    @Test
    fun `no module ships a dump its pair does not check`() {
        val checked = pairs.flatMap { (noOp, real, targets) -> targets.flatMap { listOf(dumpOf(noOp, it), dumpOf(real, it)) } }
        val modules = pairs.flatMap { listOf(it.first, it.second) }
        val shipped =
            modules.flatMap { module ->
                sdkDir
                    .resolve("$module/api")
                    .walk()
                    .filter { it.isFile }
                    .toList()
            }
        assertEquals(shipped.toSet(), checked.filter { it.isFile }.toSet())
    }

    @Test
    fun `a public member gained by either side is a difference`() {
        val real = apiOf("core", "android")
        val gained = parseDump("public final class appmonitor/sdk/core/AppMonitor {\n\tpublic static final fun pause ()V\n}")
        assertNotEquals(real, real + gained)
        val noOp = apiOf("core-no-op", "android")
        assertNotEquals(real, noOp + parseDump("public final class appmonitor/sdk/Extra {\n}"))
    }

    @Test
    fun `klib dumps compare declarations, not the library name`() {
        val header = "// Klib ABI Dump\n// Targets: [iosArm64]\n// Library unique name: <%s>\n"
        val body = "final object a/B { // a/B|null[0]\n    final fun stop() // a/B.stop|stop(){}[0]\n}\n"
        assertEquals(parseKlibDump(header.format("x:core") + body), parseKlibDump(header.format("x:core-no-op") + body))
        assertNotEquals(parseKlibDump(header.format("x") + body), parseKlibDump(header.format("x") + body.replace("stop", "pause")))
        assertNotEquals(
            parseKlibDump(header.format("x") + body),
            parseKlibDump(header.replace("[iosArm64]", "[iosArm64, iosSimulatorArm64]").format("x") + body),
        )
    }

    private fun dumpOf(
        module: String,
        target: String,
    ): File = if (target == KLIB) sdkDir.resolve("$module/api/$module.klib.api") else sdkDir.resolve("$module/api/$target/$module.api")

    private fun apiOf(
        module: String,
        target: String,
    ): Set<String> {
        val dump = dumpOf(module, target).readText()
        return if (target == KLIB) parseKlibDump(dump) else parseDump(dump)
    }

    private companion object {
        const val KLIB = "klib"
    }
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

/** [parseDump] of a klib dump without its comments, which name the library; the target list is kept. */
internal fun parseKlibDump(dump: String): Set<String> {
    val lines = dump.lines()
    val targets = lines.filter { it.startsWith("// Targets:") }.toSet()
    val declarations = lines.filterNot { it.trimStart().startsWith("//") }.joinToString("\n") { it.substringBefore(" // ") }
    return targets + parseDump(declarations)
}
