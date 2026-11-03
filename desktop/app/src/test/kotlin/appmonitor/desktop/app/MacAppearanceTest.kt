package appmonitor.desktop.app

import appmonitor.desktop.domain.ThemeMode
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacAppearanceTest {
    @Test
    fun `each theme maps to the appearance AWT reads at launch`() {
        assertEquals(
            listOf("system", "NSAppearanceNameAqua", "NSAppearanceNameDarkAqua"),
            ThemeMode.entries.map(::macAppearanceOf),
        )
    }

    @Test
    fun `a picked theme moves the running app's appearance`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        assumeFalse(GraphicsEnvironment.isHeadless())
        Toolkit.getDefaultToolkit()

        assertEquals("NSAppearanceNameDarkAqua", afterPicking(ThemeMode.Dark, "NSAppearanceNameDarkAqua"))
        assertEquals("NSAppearanceNameAqua", afterPicking(ThemeMode.Light, "NSAppearanceNameAqua"))
        assertNull(afterPicking(ThemeMode.System, null))
    }

    // AppKit applies it on its main thread, later.
    private fun afterPicking(
        theme: ThemeMode,
        expected: String?,
    ): String? {
        applyMacAppearance(theme)
        val deadline = System.currentTimeMillis() + 5_000
        while (ObjC.appearanceName() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return ObjC.appearanceName()
    }
}
