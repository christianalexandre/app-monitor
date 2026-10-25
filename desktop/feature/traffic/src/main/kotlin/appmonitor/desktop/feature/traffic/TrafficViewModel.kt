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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the window shows (ADR-0140): the live list, or one stored run read-only. */
sealed interface ShownRun {
    data object Live : ShownRun

    data class Stored(
        val id: Long,
    ) : ShownRun
}

/** The stored runs, and what the window shows; [shownRunId] is null for the live list. */
data class RunsState(
    val runs: List<Run> = emptyList(),
    val currentRun: Long = 0,
    val shown: ShownRun = ShownRun.Live,
) {
    val shownRunId: Long? get() = (shown as? ShownRun.Stored)?.id

    val title: String get() = shownRunId?.let { id -> runs.firstOrNull { it.id == id }?.let(::titleOf) ?: "" } ?: LIVE
}

/**
 * The shown list's sessions, the first [rows] that [filter] matches of the picked [session] or, when null, of every
 * device, the picked [row] and its [detail], and the exchanges [marked] for "Save as issue" (ADR-0095 A.1).
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

/** The picked request [id] of [appId], read again whenever its row changes; null [exchange] while it loads, null [id] once gone. */
data class DetailState(
    val id: Long? = null,
    val appId: String = "",
    val exchange: HttpExchange? = null,
    val copyNote: String? = null,
    val replayNote: String? = null,
    val canReplay: Boolean = false,
)

/** The traffic panes: devices, requests, one request and the runs (SDD 09); every list follows the live list or the store. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModel(
    private val runRepository: RunRepository,
    private val traffic: TrafficRepository,
    private val exports: ExportRepository,
    private val replays: ReplayRepository? = null,
) : ViewModel() {
    private val shown = MutableStateFlow<ShownRun>(ShownRun.Live)
    private val selectedSession = MutableStateFlow<Long?>(null)
    private val selectedRequest = MutableStateFlow<Long?>(null)

    // The picked request's session, whose app a replay goes through; "Show original" stays in it.
    private val pickedSession = MutableStateFlow<Long?>(null)

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
        combine(sessions, selectedSession) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // A new list, session or filter starts again from one page, as the list did when it was rebuilt.
    private val rows =
        combine(
            runs.map { it.shownRunId }.distinctUntilChanged(),
            session.map { it?.id }.distinctUntilChanged(),
            filter,
        ) { run, id, filter ->
            filter.copy(runId = run, sessionId = id)
        }.flatMapLatest { filter ->
            limit.value = TrafficRepository.PAGE_SIZE
            limit.flatMapLatest { traffic.observeExchanges(filter, it) }.onStart { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val row =
        combine(rows, selectedRequest) { rows, id -> rows.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Body state (format, scroll, frames, notes) belongs to one exchange, even if another has the same bytes.
    private val picked =
        combine(row, original) { row, original -> row?.id ?: original }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The id read, with what it read: null for an exchange no longer listed or stored.
    private val exchange =
        combine(row, picked, frameLimit) { row, id, limit -> Triple(row, id, limit) }
            .distinctUntilChanged()
            .transformLatest { (_, id, limit) -> emit(id to id?.let { traffic.exchange(it, limit) }) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null to null)

    private val refused = replays?.refused ?: MutableStateFlow(emptyMap())

    private val detail =
        combine(exchange, picked, copyNote, sent, refused) { (readId, read), id, copyNote, sent, refused ->
            val session = pickedSessionOf()
            val sessionId = session?.serverSessionId.orEmpty()
            val shown = read?.takeIf { it.id == id }
            DetailState(
                id = id.takeUnless { it != null && it == readId && read == null },
                appId = session?.hello?.app_id.orEmpty(),
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

    /** [id] null shows every device's requests. */
    fun selectSession(id: Long?) {
        original.value = null
        selectedSession.value = id
    }

    fun selectRequest(id: Long) {
        original.value = null
        pickedSession.value = rows.value.firstOrNull { it.id == id }?.sessionId
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

    /** [id] null shows the live list; a run starts on every device of it. */
    fun showRun(id: Long?) {
        original.value = null
        selectedSession.value = null
        shown.value = id?.let(ShownRun::Stored) ?: ShownRun.Live
    }

    /** Empties the live list (ADR-0140 A); a pick in it goes with it. */
    fun clear() {
        traffic.clear()
        if (shown.value == ShownRun.Live) {
            selectedRequest.value = null
            original.value = null
            val live = sessions.value.map { it.id }.toSet()
            marked.update { kept -> kept.filterValues { it.sessionId !in live } }
        }
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

    private fun pickedSessionOf(): Session? = sessions.value.firstOrNull { it.id == pickedSession.value }

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
        val requestId =
            exchange.value.second
                ?.takeIf { it.id == picked.value }
                ?.requestId ?: return
        sent.value = replays.send(pickedSessionOf()?.serverSessionId.orEmpty(), requestId)
    }

    /** After "Clear all data": the live list, nothing picked or marked. */
    fun reset() {
        shown.value = ShownRun.Live
        selectedSession.value = null
        selectedRequest.value = null
        pickedSession.value = null
        original.value = null
        marked.value = emptyMap()
    }
}

/** The live list's title (ADR-0140 A). */
internal const val LIVE = "Live"

private fun noteOf(sent: ReplaySent): String =
    when (sent) {
        ReplaySent.SENT -> "Sent to the app; its call shows as a replay"
        ReplaySent.SESSION_ENDED -> "Not sent: the app's session ended, and a replay goes through the app"
        ReplaySent.NOT_TAKEN -> "Not sent: this app's SDK does not take replays"
    }
