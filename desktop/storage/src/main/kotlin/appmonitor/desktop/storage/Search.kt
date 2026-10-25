package appmonitor.desktop.storage

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import appmonitor.v1.ResponseReceived

/**
 * What the exchange list is narrowed to (SDD 09); every null or false field matches all. [text] is a substring of the
 * URL, the host included, case-insensitive for ASCII.
 */
data class ExchangeFilter(
    val text: String? = null,
    val method: String? = null,
    val statusClass: StatusClass? = null,
    val failedOnly: Boolean = false,
    val deviceId: String? = null,
    val appId: String? = null,
    val runId: Long? = null,
    val sessionId: Long? = null,
)

/** A status code's hundreds: [from] to [from] + 99. */
enum class StatusClass(
    val from: Int,
) {
    INFORMATIONAL(100),
    SUCCESS(200),
    REDIRECTION(300),
    CLIENT_ERROR(400),
    SERVER_ERROR(500),
}

/**
 * One `WHERE` per filter set, built from the fields that are set only, so SQLite picks the index of each one
 * (ADR-0087). Listens to the tables it reads, `sessions` only when a filter joins it, like SQLDelight's generated queries.
 */
internal class SearchQuery<T : Any>(
    private val driver: SqlDriver,
    filter: ExchangeFilter,
    afterId: Long,
    limit: Long,
    columns: String,
    mapper: (SqlCursor) -> T,
) : Query<T>(mapper) {
    private val sql: String
    private val binders: List<SqlPreparedStatement.(Int) -> Unit>
    private val tables: Array<String>

    init {
        val where = mutableListOf("exchanges.id > ?")
        // One bind per `?`, added in the order the clauses are.
        val binds = mutableListOf<SqlPreparedStatement.(Int) -> Unit>({ bindLong(it, afterId) })
        val sessionWhere = mutableListOf<String>()
        filter.sessionId?.let { id ->
            where += "exchanges.session_ref = ?"
            binds += { bindLong(it, id) }
        }
        filter.runId?.let { id ->
            sessionWhere += "sessions.run_id = ?"
            binds += { bindLong(it, id) }
        }
        filter.deviceId?.let { id ->
            sessionWhere += "sessions.device_ref = (SELECT id FROM devices WHERE device_id = ?)"
            binds += { bindString(it, id) }
        }
        filter.appId?.let { id ->
            sessionWhere += "sessions.app_ref = (SELECT id FROM apps WHERE app_id = ?)"
            binds += { bindString(it, id) }
        }
        if (sessionWhere.isNotEmpty()) {
            // Sessions are few; the exchanges are then read through (session_ref, id).
            where += "exchanges.session_ref IN (SELECT id FROM sessions WHERE ${sessionWhere.joinToString(" AND ")})"
        }
        filter.method?.let { method ->
            where += "exchanges.method = ?"
            binds += { bindString(it, method) }
        }
        filter.statusClass?.let { status ->
            where += "exchanges.status_code >= ? AND exchanges.status_code < ?"
            binds += { bindLong(it, status.from.toLong()) }
            binds += { bindLong(it, status.from + 100L) }
        }
        if (filter.failedOnly) where += "exchanges.failed = 1"
        filter.text?.takeIf { it.isNotEmpty() }?.let { text ->
            where += "exchanges.url LIKE ? ESCAPE '\\'"
            binds += { bindString(it, "%${text.escapeLike()}%") }
        }
        binds += { bindLong(it, limit) }
        tables = if (sessionWhere.isEmpty()) arrayOf("exchanges") else arrayOf("exchanges", "sessions")
        sql = "SELECT $columns FROM exchanges WHERE ${where.joinToString(" AND ")} ORDER BY exchanges.id LIMIT ?"
        binders = binds
    }

    override fun addListener(listener: Listener) = driver.addListener(*tables, listener = listener)

    override fun removeListener(listener: Listener) = driver.removeListener(*tables, listener = listener)

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(null, sql, mapper, binders.size) { binders.forEachIndexed { index, bind -> bind(index) } }

    /** The query plan SQLite chooses for [sql], one line per step. */
    fun plan(): List<String> =
        driver
            .executeQuery(null, "EXPLAIN QUERY PLAN $sql", { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(3).orEmpty()) })
            }, binders.size) { binders.forEachIndexed { index, bind -> bind(index) } }
            .value

    override fun toString(): String = "Search:$sql"
}

/** The exchanges [filter] matches, as lists read them. */
internal fun exchangeSearch(
    driver: SqlDriver,
    filter: ExchangeFilter,
    afterId: Long,
    limit: Long,
) = SearchQuery(driver, filter, afterId, limit, COLUMNS, ::toStoredExchange)

/** The exchanges [filter] matches with their stored body sizes, a missing body counted as 0; a connection's byte counts. */
internal fun exchangeSizeSearch(
    driver: SqlDriver,
    filter: ExchangeFilter,
    afterId: Long,
    limit: Long,
) = SearchQuery(driver, filter, afterId, limit, SIZE_COLUMNS) { cursor ->
    val exchange = toStoredExchange(cursor)
    val closed = cursor.getBytes(17)?.let { ResponseReceived.ADAPTER.decode(it) }
    if (exchange.isConnection) {
        ExchangeSizes(exchange, requestBytes = closed?.sent_size ?: 0, responseBytes = closed?.body_size ?: 0)
    } else {
        ExchangeSizes(exchange, requestBytes = cursor.getLong(15)!!, responseBytes = cursor.getLong(16)!!)
    }
}

/** An exchange and the bytes the store holds for its bodies (`bodies.size`, after the device's cut), or a connection moved. */
data class ExchangeSizes(
    val exchange: StoredExchange,
    val requestBytes: Long,
    val responseBytes: Long,
)

private const val COLUMNS =
    "exchanges.id, exchanges.session_ref, exchanges.request_id, exchanges.method, exchanges.url, exchanges.host, " +
        "exchanges.status_code, exchanges.failed, exchanges.started_at_ms, exchanges.ended_at_ms, exchanges.capture_layer, " +
        "exchanges.error, exchanges.frame_count, exchanges.grpc_status, exchanges.mock_rule_id"

// CaptureLayer.CAPTURE_LAYER_CONNECTION, which is not a constant to Kotlin.
private const val CONNECTION_LAYER = 3

private const val SIZE_COLUMNS =
    "$COLUMNS, " +
        "coalesce((SELECT size FROM bodies WHERE exchange_ref = exchanges.id AND part = ${Ingest.PART_REQUEST}), 0), " +
        "coalesce((SELECT size FROM bodies WHERE exchange_ref = exchanges.id AND part = ${Ingest.PART_RESPONSE}), 0), " +
        "CASE WHEN exchanges.capture_layer = $CONNECTION_LAYER THEN exchanges.response_meta END"

private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun toStoredExchange(cursor: SqlCursor) =
    StoredExchange(
        cursor.getLong(0)!!,
        cursor.getLong(1)!!,
        cursor.getString(2)!!,
        cursor.getString(3),
        cursor.getString(4),
        cursor.getString(5),
        cursor.getLong(6),
        cursor.getLong(7)!!,
        cursor.getLong(8),
        cursor.getLong(9),
        cursor.getLong(10),
        cursor.getString(11),
        cursor.getLong(12)!!,
        cursor.getLong(13),
        cursor.getLong(14),
    )
