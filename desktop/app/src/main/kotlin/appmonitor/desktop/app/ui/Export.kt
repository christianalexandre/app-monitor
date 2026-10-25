package appmonitor.desktop.app.ui

import appmonitor.desktop.mcp.ExportRefused
import appmonitor.desktop.mcp.Exports
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

/** The native save dialog, [suggested] as the file name; null when cancelled. */
internal fun saveHarDialog(suggested: String): File? {
    val dialog = FileDialog(null as Frame?, "Export HAR", FileDialog.SAVE)
    dialog.file = suggested
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, if (name.endsWith(".har")) name else "$name.har")
}

/** Writes `export_har`'s log of what [filter] matches to [file]; returns the line the window shows. */
internal suspend fun exportHar(
    storage: Storage,
    filter: ExchangeFilter,
    file: File,
): String =
    withContext(Dispatchers.IO) {
        try {
            val har = Exports.har(storage, filter)
            file.writeText(har.text)
            val more = if (har.more) " (the first ${Exports.MAX_ENTRIES}; more matched)" else ""
            "Exported ${har.entries} exchanges$more to ${file.name}"
        } catch (e: ExportRefused) {
            "Not exported: ${e.message}"
        } catch (e: IOException) {
            "Not exported: ${e.message ?: e::class.simpleName}"
        }
    }

/** `to_curl`'s command for exchange [id], or why there is none. */
internal suspend fun curlOf(
    storage: Storage,
    id: Long,
): Result<String> =
    try {
        Result.success(Exports.curl(storage, id))
    } catch (e: ExportRefused) {
        Result.failure(e)
    }

internal fun harFileName(runId: Long?) = if (runId == null) "app-monitor-all-runs.har" else "app-monitor-run-$runId.har"

internal const val EXPORT_HAR = "export-har"
internal const val COPY_CURL = "copy-curl"
