package appmonitor.desktop.app.ui

import appmonitor.desktop.data.toStored
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.ExportRepository
import appmonitor.desktop.domain.HarRepository
import appmonitor.desktop.domain.HarWrite
import appmonitor.desktop.mcp.ExportRefused
import appmonitor.desktop.mcp.Exports
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

/** [ExportRepository] and [HarRepository] over the MCP tools' exports of [storage]. */
internal class StorageExports(
    private val storage: Storage,
) : ExportRepository,
    HarRepository {
    override suspend fun curl(id: Long): Result<String> = curlOf(storage, id)

    override suspend fun write(
        filter: ExchangeFilter,
        file: File,
    ): HarWrite =
        withContext(Dispatchers.IO) {
            try {
                val har = Exports.har(storage, filter.toStored())
                file.writeText(har.text)
                HarWrite.Written(har.entries, har.more, Exports.MAX_ENTRIES, har.connections)
            } catch (e: ExportRefused) {
                HarWrite.NotWritten(e.message.orEmpty())
            } catch (e: IOException) {
                HarWrite.NotWritten(e.message ?: e::class.simpleName.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HarWrite.NotWritten(e.message ?: e::class.simpleName.orEmpty())
            }
        }
}
