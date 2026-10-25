# 05 — Desktop architecture

How desktop screens are built: what they may use for layout, and where a
new look goes (ADR-0133, ADR-0134), and how a screen gets its data
(ADR-0135, ADR-0136). The layers and feature modules of
ADR-0133 join this chapter as they land (PLAN-0012).

## Rules

- **A screen draws with `desktop/designsystem` only.** Run it under
  `AppMonitorTheme`; use its components (`Text`, `TextButton`,
  `TextField`, `Checkbox`, `CheckboxRow`, `Choice`, `DialogCard`,
  `DialogActions`, `Divider`, `VerticalDivider`, `PaneTitle`,
  `Placeholder`, `Section`, `Modifier.selectableItem`, `Badge`, `Toolbar`,
  `EmptyState`, `MiddleEllipsisText`) and its tokens
  (`Spacing`, `Radius`, `Elevation`, `DialogWidth`, `TextRole`, `BadgeTone`,
  `AppMonitorTheme.colors`). `make designsystem-check` fails otherwise.
- **No Material outside the design system**: no
  `androidx.compose.material*` import, no `MaterialTheme`. A component
  the design system lacks is added there first, then used.
- **No raw color, spacing or radius in a screen**: no `Color(0x…)` or `Color.White`, no
  `.dp` in `padding`, `spacedBy`, `Spacer` or `RoundedCornerShape`. A
  value missing from `Spacing` or `Radius` is added there. Sizes
  (`width`, `heightIn`, `size`) may stay in the screen.
- **Text says what it is, not how it looks**: pick a `TextRole`, and
  `error = true` for a failure; no `style`, `fontWeight`, `fontFamily` or
  `color`.
- **A component knows no domain model.** It takes strings, numbers and
  lambdas; a type from `desktop/storage`, `desktop/server` or `protocol`
  stays in the screen.
- **A screen reads data through a `desktop/domain` repository**
  (`RunRepository`, `TrafficRepository`, `ReplayRepository`,
  `ExportRepository`, `MockRepository`, `BreakpointRepository`,
  `HeldCallRepository`, `ApprovalRepository`, `AskedReplayRepository`,
  `BenchmarkRepository`, `HarRepository`, `IssueRepository`,
  `RetentionRepository`, `AnsweredDeviceRepository`, `McpRepository`,
  `ProxyRepository`),
  never `desktop/storage`'s types (ADR-0135).
- **A screen lives in a feature module**, `desktop/feature/<name>`, which
  depends on `desktop/domain`, `desktop/ui` and `desktop/designsystem`
  only: never on `desktop/data`, `desktop/storage`, `desktop/server`,
  `desktop/mcp` or another feature. What the screen opens elsewhere comes in as labels and
  lambdas (`TrafficLinks`) from `desktop/app`.
- **What two features share goes to `desktop/ui`** (ADR-0137): how the
  window reads or writes a domain value (`METHODS`, `formatDuration`, `titleOf`,
  `statusOf`, `formatDateTime`, `utf8OrNull`, `headersOf`). A helper one feature uses stays in it.
- **Its state lives in a view model**: one `StateFlow` of an immutable
  data class, functions for events, the domain's repositories in the
  constructor. A dialog's open flag and a text being typed may stay in
  `remember`.
- **A view model is declared in its feature's Koin module**
  (`trafficModule`, `mocksModule`, `breakpointsModule`, `insightsModule`,
  `settingsModule`) and read with `koinViewModel()`; `desktop/app`'s
  `appModule` includes the module and binds the repositories (ADR-0136).
  A view model test sets `Dispatchers.Main` and runs over fake
  repositories.
- **`desktop/domain` is pure Kotlin**: `:protocol` and coroutines only;
  no Compose, SQLDelight or Ktor. A model mirrors a store row and keeps
  the protocol's messages (`Hello`, `Header`) as they are.
- **`desktop/data` maps, nothing else**: an `internal` repository over
  `Storage` and one `toDomain()` or `toStored()` per type in
  `Mappers.kt`, covered by `desktop/data`'s tests.
- **Every component is in the gallery.** A new one is added to
  `GalleryTest`, which renders it in light and dark
  (`desktop/designsystem/build/screenshots/gallery-*.png`).
- **Every color is a token with a light and a dark value** in `AppColors`
  (ADR-0145); a screen reads it through `AppMonitorTheme.colors` or a
  component, never `Color(0x…)` or `Color.White`. A screen test's
  `screenshot("x.png")` writes `x.png` and `x-dark.png` when the window
  gets `TestAppearance`.

## Examples

| Do | Don't |
| --- | --- |
| `Text(note, role = TextRole.Caption, error = true)` | `Text(note, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)` |
| `TextButton("Close", onClick = onClose)` | `TextButton(onClick = onClose) { Text("Close") }` from `androidx.compose.material` |
| `DialogCard(DialogWidth.Medium) { … DialogActions { … } }` | `Overlay { Card(Modifier.width(520.dp), elevation = 8.dp) { Column(Modifier.padding(16.dp)) { … } } }` |
| `Modifier.padding(horizontal = Spacing.lg)` | `Modifier.padding(horizontal = 12.dp)` |
| `viewModel.setFilter(it)`, the filter in `TrafficState` | `var filter by remember { … }` read by three panes |
| `TrafficViewModel(runs, traffic, exports, replays)` over `TrafficRepository` | a screen taking `storage: Storage` |
