package appmonitor.desktop.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowScope
import appmonitor.desktop.designsystem.WindowBar
import java.awt.Frame
import javax.swing.JRootPane

internal const val MAC_FULL_WINDOW_CONTENT = "apple.awt.fullWindowContent"
internal const val MAC_TRANSPARENT_TITLE_BAR = "apple.awt.transparentTitleBar"
internal const val MAC_TITLE_VISIBLE = "apple.awt.windowTitleVisible"

/**
 * On macOS, lays the window's content under its title bar, which keeps only the system's buttons and no title text
 * (ADR-0213); true when it did. Elsewhere the system's title bar stays and nothing is set.
 */
internal fun unifyTitleBar(
    rootPane: JRootPane,
    os: String = System.getProperty("os.name"),
): Boolean {
    if (!os.startsWith("Mac")) return false
    rootPane.putClientProperty(MAC_FULL_WINDOW_CONTENT, true)
    rootPane.putClientProperty(MAC_TRANSPARENT_TITLE_BAR, true)
    rootPane.putClientProperty(MAC_TITLE_VISIBLE, false)
    return true
}

/** The bar drawn in the title bar's place: its empty parts move [scope]'s window, and a double click there zooms it. */
internal fun macWindowBar(scope: WindowScope): WindowBar =
    WindowBar(WindowBar.MacButtonsInset) { modifier, content ->
        scope.WindowDraggableArea(modifier.zoomOnDoubleClick { zoom(scope.window as? Frame) }) { content() }
    }

// Outside WindowDraggableArea's own input, so the drag sees the press first; a button under the pointer takes it before both.
internal fun Modifier.zoomOnDoubleClick(zoom: () -> Unit): Modifier = pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom() }) }

/** What the system's title bar does on a double click: AWT zooms a decorated macOS window for either state. */
internal fun zoom(frame: Frame?) {
    frame ?: return
    frame.extendedState = if (frame.extendedState and Frame.MAXIMIZED_BOTH != 0) Frame.NORMAL else Frame.MAXIMIZED_BOTH
}
