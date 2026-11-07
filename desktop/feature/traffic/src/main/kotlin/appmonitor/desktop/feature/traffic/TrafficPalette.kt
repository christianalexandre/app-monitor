package appmonitor.desktop.feature.traffic

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import appmonitor.desktop.designsystem.AppIcons
import appmonitor.desktop.designsystem.CommandPalette
import appmonitor.desktop.designsystem.PaletteGroup
import appmonitor.desktop.designsystem.PaletteItem
import appmonitor.desktop.domain.appLabel
import appmonitor.v1.Platform

/** What the window's palette and its shortcuts run (ADR-0206): each one a button or a menu of the window runs too. */
internal class TrafficCommands(
    private val state: TrafficState,
    private val links: TrafficLinks,
    private val viewModel: TrafficViewModel,
    private val copyCurl: () -> Unit,
) {
    val live: Boolean get() = state.runs.shownRunId == null
    private val chosen: Boolean get() = state.marked.isNotEmpty() || state.row != null

    /** Record or Stop, on the live list only (⌘R). */
    fun record(): Boolean {
        if (!live) return false
        if (state.runs.recording == null) viewModel.record() else viewModel.stop()
        return true
    }

    /** Save of the marked or picked live requests (⌘S). */
    fun save(): Boolean {
        if (!live || !chosen) return false
        viewModel.save()
        return true
    }

    fun exportHar(): Boolean = true.also { links.onExportHar() }

    fun settings(): Boolean = true.also { links.onSettings() }

    /** The title bar's tools (ADR-0210), in its order: each runs what the palette's entry of the same name runs. */
    fun tools(): List<Tool> {
        val shortcut = { key: String -> " $SHORTCUT_PREFIX$key" }
        return listOfNotNull(
            Tool("Save" + shortcut("S"), SAVE, AppIcons.Save, { save() }, enabled = chosen).takeIf { live },
            Tool("Export HAR" + shortcut("E"), EXPORT_HAR, AppIcons.Export, links.onExportHar),
            Tool("Issues", ISSUES, AppIcons.Issue, links.onIssues),
            Tool("Benchmarks", BENCHMARKS, AppIcons.Chart, links.onBenchmarks),
            Tool("Compare runs", COMPARE_RUNS, AppIcons.Compare, links.onCompareRuns, enabled = state.runs.recordings.size > 1),
            Tool(links.mocksLabel, MOCKS, AppIcons.Mask, links.onMocks),
            Tool(links.breakpointsLabel, BREAKPOINTS, AppIcons.Pause, links.onBreakpoints),
            Tool("Settings" + shortcut(","), SETTINGS, AppIcons.Gear, links.onSettings),
        )
    }

    /** The palette's groups for [query]: Requests, Actions, Open and Devices, an empty one left out. */
    fun groups(query: String): List<PaletteGroup> {
        val exchange = state.detail.exchange?.takeIf { it.id == state.detail.id }
        val requests =
            endpointsOf(state.rows, query).map { row ->
                val parts = urlPartsOf(row.url.orEmpty())
                PaletteItem(
                    parts.path,
                    PALETTE + REQUEST + row.id,
                    AppIcons.Globe,
                    { viewModel.selectRequest(row.id) },
                    sub = "${row.method ?: "—"} · ${parts.host}",
                )
            }
        val actions =
            listOfNotNull(
                (if (state.runs.recording == null) "Record" else "Stop recording")
                    .takeIf { live }
                    ?.let {
                        PaletteItem(
                            it,
                            PALETTE + RECORD,
                            if (state.runs.recording == null) AppIcons.Record else AppIcons.Stop,
                            { record() },
                            shortcut = SHORTCUT_PREFIX + "R",
                        )
                    },
                PaletteItem(
                    if (state.marked.isEmpty()) "Save" else "Save marked requests",
                    PALETTE + SAVE,
                    AppIcons.Save,
                    { save() },
                    shortcut =
                        SHORTCUT_PREFIX + "S",
                ).takeIf { live && chosen },
                PaletteItem("Save as issue…", PALETTE + SAVE_AS_ISSUE, AppIcons.Issue, links.onSaveAsIssue).takeIf { chosen },
                exchange?.takeIf { it.mockable }?.let {
                    PaletteItem(
                        "Mock this request…",
                        PALETTE + MOCK_THIS,
                        AppIcons.Mask,
                        { links.onMock(it) },
                    )
                },
                exchange
                    ?.takeIf { state.detail.canReplay && it.replayable }
                    ?.let { PaletteItem(replayButtonOf(it.method), PALETTE + SEND_AGAIN, AppIcons.Replay, viewModel::sendAgain) },
                exchange?.takeIf { it.connection == null }?.let { PaletteItem("Copy as cURL", PALETTE + CURL, AppIcons.Copy, copyCurl) },
                PaletteItem("Export HAR…", PALETTE + EXPORT_HAR, AppIcons.Export, links.onExportHar, shortcut = SHORTCUT_PREFIX + "E"),
                PaletteItem("Clear live list", PALETTE + CLEAR, AppIcons.Trash, viewModel::clear).takeIf { live },
            ).filter { matches(it.label, query) }
        val open =
            listOfNotNull(
                PaletteItem("Issues", PALETTE + ISSUES, AppIcons.Issue, links.onIssues),
                PaletteItem("Benchmarks", PALETTE + BENCHMARKS, AppIcons.Chart, links.onBenchmarks),
                PaletteItem(links.mocksLabel, PALETTE + MOCKS, AppIcons.Mask, links.onMocks),
                PaletteItem(links.breakpointsLabel, PALETTE + BREAKPOINTS, AppIcons.Pause, links.onBreakpoints),
                PaletteItem("Compare runs", PALETTE + COMPARE_RUNS, AppIcons.RecordOutline, links.onCompareRuns)
                    .takeIf { state.runs.recordings.size > 1 },
                PaletteItem("Settings", PALETTE + SETTINGS, AppIcons.Gear, links.onSettings, shortcut = SHORTCUT_PREFIX + ","),
            ).filter { matches(it.label, query) }
        val devices =
            state.sessions
                .map { session ->
                    val hello = session.hello
                    PaletteItem(
                        "Show ${hello.device_name}",
                        PALETTE + DEVICE + session.id,
                        if (hello.platform == Platform.PLATFORM_ANDROID) AppIcons.Android else AppIcons.Phone,
                        { viewModel.selectSession(session.id) },
                        sub = "${hello.appLabel} · ${hello.os_version}",
                    )
                }.filter { matches(it.label + " " + it.sub, query) }
        return listOf(
            PaletteGroup("Requests", requests),
            PaletteGroup("Actions", actions),
            PaletteGroup("Open", open),
            PaletteGroup("Devices", devices),
        ).filter { it.items.isNotEmpty() }
    }
}

/** One of the title bar's tools: its [name] (with its shortcut), the [tag] after `TOOL`, its [icon], what it runs. */
internal class Tool(
    val name: String,
    val tag: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/** The palette over the window, filled by [commands] (ADR-0206). */
@Composable
internal fun TrafficPalette(
    commands: TrafficCommands,
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
) {
    CommandPalette(
        query,
        onQuery,
        commands.groups(query),
        onClose,
        Modifier.testTag(PALETTE_OPEN),
        closeShortcut = SHORTCUT_PREFIX + "K",
    )
}
