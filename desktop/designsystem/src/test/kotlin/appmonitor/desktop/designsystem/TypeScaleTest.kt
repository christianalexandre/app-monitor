package appmonitor.desktop.designsystem

import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeScaleTest {
    @Test
    fun `the scale is the mockup's sizes, not Material's mobile defaults`() {
        assertEquals(12.5.sp, styleOf(TextRole.Body).fontSize)
        assertEquals(11.5.sp, styleOf(TextRole.Small).fontSize)
        assertEquals(10.5.sp, styleOf(TextRole.Caption).fontSize)
        assertEquals(10.sp, styleOf(TextRole.Label).fontSize)
        assertEquals(0.08.em, styleOf(TextRole.Label).letterSpacing)
        assertEquals(11.5.sp, styleOf(TextRole.Code).fontSize)
    }

    @Test
    fun `every role has an explicit line height and no button label is spaced`() {
        TextRole.entries.forEach { assert(styleOf(it).lineHeight.isSp) { "${it.name} has no line height" } }
        TextRole.entries.filter { it != TextRole.Label }.forEach { assertEquals(0.em, styleOf(it).letterSpacing, it.name) }
    }
}
