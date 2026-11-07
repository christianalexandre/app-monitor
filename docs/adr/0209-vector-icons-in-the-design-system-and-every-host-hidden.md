---
id: ADR-0209
title: Vector icons in the design system, and a filter that hides every host
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0209 — Vector icons in the design system, and a filter that hides every host

## Context

PLAN-0018 H8.9. The window's buttons were Unicode glyphs drawn as text
(`GlyphButton`: `◧`, `▢`, `⌕`, `⧉`, `✎`, `⋯`, `↑`, `↓`; the palette's
`●`, `≈`, `⇪`…). The owner's first look (2026-11-07) found the "show
sidebar" toggle drawn as a broken square, because the font has no `▢`,
and the copy and search glyphs too small next to the bar's other controls.

Evidence read:

- The mockup (`docs/screenshots/h8-mockup.html`, `Ic`) draws every icon as
  a 16 × 16 SVG: strokes 1.25 wide with round ends and joins, a few dots
  filled, tinted by the caller, 13 px in the sidebar and 14 px in the bars.
  Its sidebar gives Live the live icon, the recording a red record icon,
  a stored run the record icon, Saved a star, a shown host a check in the
  accent color and a hidden one a globe in the tertiary color.
- Compose Multiplatform 1.12.2 builds an `ImageVector` from SVG path data
  (`ImageVector.Builder.addPath`, `addPathNodes` in `ui-desktop`, the
  stroke's width, cap and join on the path) and draws it with
  `rememberVectorPainter`. No icon library is needed, and Material's icons
  would put Material back in the screens (ADR-0133).
- The mockup's arcs use SVG's packed flags (`a5 5 0 000 7`). The paths
  here are written with the flags apart, so no parser's reading of the
  packed form is relied on.
- The store's host filter keeps the hosts picked, none picked keeping all
  (ADR-0148). ADR-0205 therefore kept the last host shown: "every host
  hidden" could not be said. The mockup hides any host, the last one too.

## Options

1. **Vector icons in `desktop/designsystem`, from the mockup's paths; a
   `noHost` flag on the filter.** As the mockup. The flag is one field in
   the domain's and the store's `ExchangeFilter`, one `WHERE 0` and one
   clause of the live matcher; no schema change.
2. **An icon font or an icon library.** A new dependency and a license to
   check, for 27 icons the mockup already draws.
3. **Keep the last host shown; the HOSTS button only shows every host.**
   No change below the window, but the button the plan asks for would
   only half work.

## Decision

Option 1.

- **`AppIcons`** in `desktop/designsystem/Icons.kt`: Sidebar, Live, Record,
  Star, Globe, Check, Search, Copy, Gear, Trash, Save,
  Issue, Chart, Mask, Pause, Export, Replay, Ellipsis, Bolt, Phone, Android
  and Filter from the mockup's paths; ChevronUp and ChevronDown (the
  mockup's chevron turned) for a search's previous and next match; Pencil,
  Stop and RecordOutline, which the mockup does not draw, in its stroke.
  Each is built once, lazily.
- **`Icon`** draws one at `IconSize.small` (13 dp), `medium` (14 dp) or
  `large` (16 dp, the palette's field), tinted, `neutral` by default.
  **`IconButton`** replaces `GlyphButton`: a 14 dp icon in 4 dp of
  padding, with its description for screen readers. `MenuButton` and
  `PaletteItem` take an icon. `SidebarItem` takes an icon and its tint
  beside the dot; `SidebarSection` takes a trailing slot.
- **Sidebar**: Live has the live icon, the recording the red record icon,
  a stored run the record outline, Saved the star; a host has a check in
  the primary (accent) color while shown and the globe in the muted color
  while hidden. HOSTS' label has a button: "Hide every host" (a check)
  while any host is shown, "Show every host" (a globe) once all are hidden.
- **Hosts**: `ExchangeFilter.noHost` keeps no row. Hiding the last host
  shown sets it, which replaces ADR-0205's "the last host shown cannot be
  hidden"; showing a host while it is set picks that host alone. The
  domain maps it to the store's filter, where both the SQL search and the
  live matcher apply it (`FilterMatchTest` runs it on both).
- **Palette** entries get icons: a request the globe, Record and Stop,
  Save, Issue for "Save as issue" and Issues, Mask for mocks, Replay,
  Copy for cURL, Export, Trash for Clear, Chart for Benchmarks, Pause for
  Breakpoints, the record outline for Compare runs, Gear, and Android or
  Phone for a device by its platform.

## Consequences

- The window no longer depends on a font having a glyph; the icons are the
  same on macOS, Windows and Linux.
- A new icon is a path in `Icons.kt`, in the gallery and in `IconsTest`
  through `AppIcons.all`.
- `GlyphButton` is gone; arrows and dots that are text (a frame's "↑ sent",
  the run diff's "●") stay text.
- The store's filter gains a field that only the window sets; the MCP
  tools never set it.
