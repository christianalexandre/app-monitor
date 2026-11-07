package appmonitor.desktop.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where [TitleBar] sits in its window (ADR-0213): [buttonsInset] kept free at its start for the system's window buttons, and
 * [area] drawn around it, so the window's own title bar can move the window from the bar's empty parts.
 */
@Immutable
class WindowBar(
    val buttonsInset: Dp,
    val area: @Composable (modifier: Modifier, content: @Composable () -> Unit) -> Unit,
) {
    companion object {
        /** Under the system's own title bar: nothing kept, the bar does not move the window. */
        val Plain = WindowBar(0.dp) { modifier, content -> Box(modifier) { content() } }

        /** macOS's close, minimize and zoom buttons, from the window's edge to the zoom button's end, with a gap. */
        val MacButtonsInset: Dp = 72.dp
    }
}

/** The window's [WindowBar], [WindowBar.Plain] unless the window draws its own title bar. */
val LocalWindowBar = staticCompositionLocalOf { WindowBar.Plain }
