package appmonitor.desktop.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.ExportRepository
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.ReplayKey
import appmonitor.desktop.domain.ReplayRepository
import appmonitor.desktop.domain.ReplaySent
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.ui.titleOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which run the window shows (ADR-0084 C.1); [Current] follows "New run". */
sealed interface ShownRun {
    data object Current : ShownRun

    data object All : ShownRun

    data class Earlier(
        val id: Long,
    ) : ShownRun
}

/** The runs, and the one the window shows; [shownRunId] is null for "All runs". */
data class RunsState(
    val runs: List<Run> = emptyList(),
    val currentRun: Long = 0,
    val shown: ShownRun = ShownRun.Current,
) {
    val shownRunId: Long? get() = shownRunIdOf(shown, currentRun)

    val title: String get() = if (shownRunId == null) "All runs" else runs.firstOrNull { it.id == shownRunId }?.let(::titleOf) ?: ""
}

/**
 * The shown run's sessions, the picked [session]'s first [rows] that [filter] matches, the picked [row] and its
 * [detail], and the exchanges [marked] for "Save as issue" from any session or run (ADR-0095 A.1).
 */
data class TrafficState(
    val runs: RunsState = RunsState(),
    val sessions: List<Session> = emptyList(),
    val session: Session? = null,
    val health: StoreHealth = StoreHealth(),
    val filter: ExchangeFilter = ExchangeFilter(),
    val rows: List<Exchange> = emptyList(),
    val row: Exchange? = null,
    val marked: Map<Long, Exchange> = emptyMap(),
    val detail: DetailState = DetailState(),
)

/** The picked request [id], read again whenever its row changes; null [exchange] while it loads. */
data class DetailState(
    val id: Long? = null,
    val exchange: HttpExchange? = null,
    val copyNote: String? = null,
    val replayNote: String? = null,
    val canReplay: Boolean = false,
)

/** The traffic panes: devices, requests, one request and the runs (SDD 09); every list follows the store's commits. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModel(
    private val runRepository: RunRepository,
    private val traffic: TrafficRepository,
    private val exports: ExportRepository,
    private val replays: ReplayRepository? = null,
) : ViewModel() {
    private val shown = MutableStateFlow<ShownRun>(ShownRun.Current)
    private val selectedSession = MutableStateFlow<Long?>(null)
    private val selectedRequest = MutableStateFlow<Long?>(null)

    // "Show original"'s pick, read by id while its row is not among the loaded pages.
    private val original = MutableStateFlow<Long?>(null)
    private val filter = MutableStateFlow(ExchangeFilter())
    private val marked = MutableStateFlow(emptyMap<Long, Exchange>())
    private val limit = MutableStateFlow(TrafficRepository.PAGE_SIZE)
    private val frameLimit = MutableStateFlow(TrafficRepository.PAGE_SIZE)
    private val copyNote = MutableStateFlow<String?>(null)
    private val sent = MutableStateFlow<ReplaySent?>(null)

    private val runs =
        combine(runRepository.observeRuns().onStart { emit(emptyList()) }, runRepository.currentRun, shown, ::RunsState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, RunsState(currentRun = runRepository.currentRun.value))

    private val sessions =
        runs
            .map { it.shownRunId }
            .distinctUntilChanged()
            .flatMapLatest { traffic.observeSessions(it).onStart { emit(emptyList()) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val session =
        combine(sessions, selectedSession) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // A new session or filter starts again from one page, as the list did when it was rebuilt.
    private val rows =
        combine(session.map { it?.id }.distinctUntilChanged(), filter) { id, filter -> id to filter }
            .flatMapLatest { (id, filter) ->
                if (id == null) {
                    flowOf(emptyList())
                } else {
                    limit.value = TrafficRepository.PAGE_SIZE
                    limit.flatMapLatest { traffic.observeExchanges(filter.copy(sessionId = id), it) }.onStart { emit(emptyList()) }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val row =
        combine(rows, selectedRequest, session) { rows, id, session -> rows.firstOrNull { it.id == id && it.sessionId == session?.id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Body state (format, scroll, frames, notes) belongs to one exchange, even if another has the same bytes.
    private val picked =
        combine(row, original) { row, original -> row?.id ?: original }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val exchange =
        combine(row, picked, frameLimit) { row, id, limit -> Triple(row, id, limit) }
            .distinctUntilChanged()
            .transformLatest { (_, id, limit) -> emit(id?.let { traffic.exchange(it, limit) }) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val refused = replays?.refused ?: MutableStateFlow(emptyMap())

    private val detail =
        combine(exchange, picked, copyNote, sent, refused) { exchange, id, copyNote, sent, refused ->
            val sessionId = session.value?.serverSessionId.orEmpty()
            val shown = exchange?.takeIf { it.id == id }
            DetailState(
                id = id,
                exchange = shown,
                copyNote = copyNote,
                replayNote =
                    shown?.let { refused[ReplayKey(sessionId, it.requestId)] }?.let { "The app did not send it again: $it" }
                        ?: sent?.let(::noteOf),
                canReplay = replays != null,
            )
        }

    private val lists =
        combine(
            runs,
            sessions,
            session,
            traffic.health.onStart { emit(StoreHealth()) },
            filter,
        ) { runs, sessions, session, health, filter ->
            TrafficState(runs, sessions, session, health, filter)
        }

    val state: StateFlow<TrafficState> =
        combine(lists, rows, row, marked, detail) { lists, rows, row, marked, detail ->
            lists.copy(rows = rows, row = row, marked = marked, detail = detail)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TrafficState(runs = runs.value))

    init {
        viewModelScope.launch {
            picked.collect {
                frameLimit.value = TrafficRepository.PAGE_SIZE
                copyNote.value = null
                sent.value = null
            }
        }
    }

    fun selectSession(id: Long) {
        original.value = null
        selectedSession.value = id
    }

    fun selectRequest(id: Long) {
        original.value = null
        selectedRequest.value = id
    }

    fun setFilter(value: ExchangeFilter) {
        original.value = null
        filter.value = value
    }

    /** Asks for one more page once the last one is full. */
    fun loadMore() {
        if (rows.value.size >= limit.value) limit.value += TrafficRepository.PAGE_SIZE
    }

    fun mark(
        exchange: Exchange,
        on: Boolean,
    ) {
        marked.update { if (on) it + (exchange.id to exchange) else it - exchange.id }
    }

    /** The marked exchanges, or the picked one when none is. */
    fun toSave(): Map<Long, Exchange> = marked.value.ifEmpty { row.value?.let { mapOf(it.id to it) }.orEmpty() }

    fun clearMarked() {
        marked.value = emptyMap()
    }

    /** [id] null shows every run. */
    fun showRun(id: Long?) {
        original.value = null
        shown.value =
            when (id) {
                null -> ShownRun.All
                runRepository.currentRun.value -> ShownRun.Current
                else -> ShownRun.Earlier(id)
            }
    }

    fun newRun() {
        viewModelScope.launch { runRepository.newRun() }
        original.value = null
        shown.value = ShownRun.Current
    }

    fun rename(
        id: Long,
        name: String,
    ) {
        viewModelScope.launch { runRepository.rename(id, name) }
    }

    /** Picks request [id] of the shown session under no filter, loaded or not. */
    fun showOriginal(id: Long) {
        filter.value = ExchangeFilter()
        selectedRequest.value = id
        original.value = id
    }

    fun moreFrames() {
        frameLimit.value += TrafficRepository.PAGE_SIZE
    }

    /** Hands the picked request's cURL command to [copy]; the note says what happened. */
    fun copyCurl(copy: (String) -> Unit) {
        val id = picked.value ?: return
        viewModelScope.launch {
            copyNote.value =
                exports.curl(id).fold(
                    { command ->
                        copy(command)
                        "Copied"
                    },
                    { it.message },
                )
        }
    }

    fun sendAgain() {
        val replays = replays ?: return
        val requestId = exchange.value?.takeIf { it.id == picked.value }?.requestId ?: return
        sent.value = replays.send(session.value?.serverSessionId.orEmpty(), requestId)
    }

    /** After "Clear all data": the current run, nothing picked or marked. */
    fun reset() {
        shown.value = ShownRun.Current
        selectedSession.value = null
        selectedRequest.value = null
        original.value = null
        marked.value = emptyMap()
    }
}

private fun shownRunIdOf(
    shown: ShownRun,
    currentRun: Long,
): Long? =
    when (shown) {
        ShownRun.Current -> currentRun
        ShownRun.All -> null
        is ShownRun.Earlier -> shown.id
    }

private fun noteOf(sent: ReplaySent): String =
    when (sent) {
        ReplaySent.SENT -> "Sent to the app; its call shows as a replay"
        ReplaySent.SESSION_ENDED -> "Not sent: the app's session ended, and a replay goes through the app"
        ReplaySent.NOT_TAKEN -> "Not sent: this app's SDK does not take replays"
    }
