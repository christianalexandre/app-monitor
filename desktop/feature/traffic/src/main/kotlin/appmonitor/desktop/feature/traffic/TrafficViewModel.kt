package appmonitor.desktop.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.DeviceConnectionRepository
import appmonitor.desktop.domain.DisconnectedDevice
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

/** What the window shows (ADR-0140): the live list, or one stored run read-only. */
sealed interface ShownRun {
    data object Live : ShownRun

    data class Stored(
        val id: Long,
    ) : ShownRun
}

/**
 * The stored runs, the one being [recording], what the window shows and what the last Save did; [shownRunId] is null for
 * the live list.
 */
data class RunsState(
    val runs: List<Run> = emptyList(),
    val recording: Long? = null,
    val shown: ShownRun = ShownRun.Live,
    val saveNote: String? = null,
) {
    val shownRunId: Long? get() = (shown as? ShownRun.Stored)?.id

    /** The recordings, newest first, without "Saved". */
    val recordings: List<Run> get() = runs.filterNot { it.saved }

    /** The "Saved" collection, null before the first Save (ADR-0140 C). */
    val saved: Run? get() = runs.firstOrNull { it.saved }

    val title: String get() = shownRunId?.let { id -> runs.firstOrNull { it.id == id }?.let(::titleOf) ?: "" } ?: LIVE
}

/**
 * The shown list's sessions, the first [rows] that [filter] matches of the picked [session] or, when null, of every
 * device, the picked [row] and its [detail], the exchanges [marked] for "Save as issue" (ADR-0095 A.1) and the devices
 * [disconnected] (ADR-0144); [canDisconnect] is false with nobody to ask. [hosts] are the shown list's, and the
 * filter's own ones gone from it, for the filter sheet.
 */
data class TrafficState(
    val runs: RunsState = RunsState(),
    val sessions: List<Session> = emptyList(),
    val session: Session? = null,
    val health: StoreHealth = StoreHealth(),
    val filter: ExchangeFilter = ExchangeFilter(),
    val hosts: List<String> = emptyList(),
    val rows: List<Exchange> = emptyList(),
    val row: Exchange? = null,
    val marked: Map<Long, Exchange> = emptyMap(),
    val detail: DetailState = DetailState(),
    val disconnected: List<DisconnectedDevice> = emptyList(),
    val canDisconnect: Boolean = false,
) {
    /** Whether [session]'s app on its device is refused until Reconnect. */
    fun refuses(session: Session): Boolean = disconnected.any { it.deviceId == session.hello.device_id && it.appId == session.hello.app_id }
}

/**
 * The picked request [id] of [appId], read again whenever its row changes; null [exchange] while it loads, null [id] once
 * gone. [curl] is the cURL tab's command, or why there is none, null while it loads or another tab is shown.
 */
data class DetailState(
    val id: Long? = null,
    val appId: String = "",
    val exchange: HttpExchange? = null,
    val copyNote: String? = null,
    val replayNote: String? = null,
    val canReplay: Boolean = false,
    val tab: DetailTab = DetailTab.Summary,
    val curl: Result<String>? = null,
)

/** The traffic panes: devices, requests, one request and the runs (SDD 09); every list follows the live list or the store. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModel(
    private val runRepository: RunRepository,
    private val traffic: TrafficRepository,
    private val exports: ExportRepository,
    private val replays: ReplayRepository? = null,
    private val connections: DeviceConnectionRepository? = null,
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
    private val tab = MutableStateFlow(DetailTab.Summary)
    private val sent = MutableStateFlow<ReplaySent?>(null)
    private val saveNote = MutableStateFlow<String?>(null)

    private val runs =
        combine(runRepository.observeRuns().onStart { emit(emptyList()) }, runRepository.recording, shown, saveNote, ::RunsState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, RunsState(recording = runRepository.recording.value))

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

    private val hosts =
        combine(runs.map { it.shownRunId }.distinctUntilChanged(), session.map { it?.id }.distinctUntilChanged(), ::Pair)
            .flatMapLatest { (run, id) -> traffic.observeHosts(run, id).onStart { emit(emptyList()) } }
            .combine(filter) { hosts, filter -> (hosts + filter.hosts).distinct().sorted() }

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

    private val curl =
        combine(picked, tab) { id, tab -> id.takeIf { tab == DetailTab.Curl } }
            .distinctUntilChanged()
            .transformLatest { id ->
                emit(null)
                if (id != null) emit(exports.curl(id))
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        }.combine(combine(tab, curl, ::Pair)) { detail, (tab, curl) -> detail.copy(tab = tab, curl = curl) }

    private val lists =
        combine(
            runs,
            sessions,
            session,
            traffic.health.onStart { emit(StoreHealth()) },
            filter,
        ) { runs, sessions, session, health, filter ->
            TrafficState(runs, sessions, session, health, filter)
        }.combine(connections?.disconnected ?: flowOf(emptyList())) { lists, disconnected ->
            lists.copy(disconnected = disconnected, canDisconnect = connections != null)
        }.combine(hosts) { lists, hosts -> lists.copy(hosts = hosts) }

    val state: StateFlow<TrafficState> =
        combine(lists, rows, row, marked, detail) { lists, rows, row, marked, detail ->
            lists.copy(rows = rows, row = row, marked = marked, detail = detail)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TrafficState(runs = runs.value, canDisconnect = connections != null))

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

    /** Stores what [toSave] gives in "Saved" (ADR-0140 C); the saved ones are unmarked. */
    fun save() {
        val ids = toSave().keys
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val saved = traffic.save(ids)
            marked.update { it - saved.keys }
            saveNote.value = if (saved.size == ids.size) "Saved ${saved.size} to Saved" else "Saved ${saved.size} of ${ids.size} to Saved"
        }
    }

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
        saveNote.value = null
        if (shown.value == ShownRun.Live) {
            selectedRequest.value = null
            original.value = null
            val live = sessions.value.map { it.id }.toSet()
            marked.update { kept -> kept.filterValues { it.sessionId !in live } }
        }
    }

    /** Starts recording into a new run (ADR-0140 B); the window stays on the live list. */
    fun record() {
        viewModelScope.launch { runRepository.record() }
    }

    fun stop() {
        viewModelScope.launch { runRepository.stop() }
    }

    fun rename(
        id: Long,
        name: String,
    ) {
        viewModelScope.launch { runRepository.rename(id, name) }
    }

    /** Closes [session]'s device and refuses it until [reconnect] (ADR-0140 E). */
    fun disconnect(session: Session) {
        val connections = connections ?: return
        viewModelScope.launch { connections.disconnect(session.hello) }
    }

    fun reconnect(device: DisconnectedDevice) {
        val connections = connections ?: return
        viewModelScope.launch { connections.reconnect(device.deviceId, device.appId) }
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

    fun selectTab(tab: DetailTab) {
        this.tab.value = tab
    }

    /** Hands [part] of the picked request to [copy]; the note says what happened. */
    fun copy(
        part: DetailPart,
        copy: (String) -> Unit,
    ) {
        val id = picked.value ?: return
        viewModelScope.launch {
            val text =
                if (part == DetailPart.Curl) {
                    exports.curl(id)
                } else {
                    exchange.value.second
                        ?.takeIf { it.id == id }
                        ?.let { textOf(it, part) }
                        ?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Nothing to copy"))
                }
            copyNote.value =
                text.fold(
                    { copied ->
                        copy(copied)
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
