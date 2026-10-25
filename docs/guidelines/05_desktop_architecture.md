# 05 — Desktop architecture

How desktop screens are built: what they may use for layout, and where a
new look goes (ADR-0133, ADR-0134). The layers and feature modules of
ADR-0133 join this chapter as they land (PLAN-0012).

## Rules

- **A screen draws with `desktop/designsystem` only.** Run it under
  `AppMonitorTheme`; use its components (`Text`, `TextButton`,
  `TextField`, `Checkbox`, `CheckboxRow`, `Choice`, `DialogCard`,
  `DialogActions`, `Divider`, `VerticalDivider`, `PaneTitle`,
  `Placeholder`, `Section`, `Modifier.selectableItem`) and its tokens
  (`Spacing`, `Radius`, `Elevation`, `DialogWidth`, `TextRole`,
  `AppMonitorTheme.colors`). `make designsystem-check` fails otherwise.
- **No Material outside the design system**: no
  `androidx.compose.material*` import, no `MaterialTheme`. A component
  the design system lacks is added there first, then used.
- **No raw color, spacing or radius in a screen**: no `Color(0x…)`, no
  `.dp` in `padding`, `spacedBy`, `Spacer` or `RoundedCornerShape`. A
  value missing from `Spacing` or `Radius` is added there. Sizes
  (`width`, `heightIn`, `size`) may stay in the screen.
- **Text says what it is, not how it looks**: pick a `TextRole`, and
  `error = true` for a failure; no `style`, `fontWeight`, `fontFamily` or
  `color`.
- **A component knows no domain model.** It takes strings, numbers and
  lambdas; a type from `desktop/storage`, `desktop/server` or `protocol`
  stays in the screen.
- **Every component is in the gallery.** A new one is added to
  `GalleryTest`, which renders it in light and dark
  (`desktop/designsystem/build/screenshots/gallery-*.png`).

## Examples

| Do | Don't |
| --- | --- |
| `Text(note, role = TextRole.Caption, error = true)` | `Text(note, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)` |
| `TextButton("Close", onClick = onClose)` | `TextButton(onClick = onClose) { Text("Close") }` from `androidx.compose.material` |
| `DialogCard(DialogWidth.Medium) { … DialogActions { … } }` | `Overlay { Card(Modifier.width(520.dp), elevation = 8.dp) { Column(Modifier.padding(16.dp)) { … } } }` |
| `Modifier.padding(horizontal = Spacing.lg)` | `Modifier.padding(horizontal = 12.dp)` |
