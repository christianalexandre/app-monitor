package appmonitor.desktop.app

import appmonitor.desktop.domain.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class MacAppearanceTest {
    @Test
    fun `each theme maps to the appearance AWT reads at launch`() {
        assertEquals(
            listOf("system", "NSAppearanceNameAqua", "NSAppearanceNameDarkAqua"),
            ThemeMode.entries.map(::macAppearanceOf),
        )
    }
}
