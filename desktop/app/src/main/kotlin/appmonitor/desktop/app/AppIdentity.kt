package appmonitor.desktop.app

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.Taskbar
import javax.imageio.ImageIO

internal const val APP_NAME = "App Monitor"
internal const val MAC_APP_NAME = "apple.awt.application.name"
internal const val APP_ICON = "app-monitor.png"

private fun iconBytes(): ByteArray =
    checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(APP_ICON)) { "$APP_ICON missing" }.use { it.readBytes() }

/** The window's icon, from the PNG `appIcons` renders (ADR-0211). */
internal fun appIconPainter(): Painter = BitmapPainter(Image.makeFromEncoded(iconBytes()).toComposeImageBitmap())

// ADR-0211: AWT reads the name once, when its toolkit starts; set later, the menu bar says `MainKt`.
internal fun nameApp() {
    System.setProperty(MAC_APP_NAME, APP_NAME)
}

// ADR-0211: starts AWT's toolkit, so after every property it reads once (ADR-0150).
internal fun showDockIcon() {
    if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
        Taskbar.getTaskbar().iconImage = ImageIO.read(iconBytes().inputStream())
    }
}
