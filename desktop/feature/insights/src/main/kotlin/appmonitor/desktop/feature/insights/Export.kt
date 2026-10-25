package appmonitor.desktop.feature.insights

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The native save dialog, [suggested] as the file name; null when cancelled. */
fun saveHarDialog(suggested: String): File? {
    val dialog = FileDialog(null as Frame?, "Export HAR", FileDialog.SAVE)
    dialog.file = suggested
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, if (name.endsWith(".har")) name else "$name.har")
}

fun harFileName(runId: Long?) = if (runId == null) "app-monitor-all-runs.har" else "app-monitor-run-$runId.har"
