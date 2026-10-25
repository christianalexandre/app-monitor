---
id: ADR-0134
title: The design system wraps Compose Material and is checked by a script
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0134 — The design system wraps Compose Material and is checked by a script

## Context

ADR-0133 creates `desktop/designsystem` and a rule script in `make check`.
Its context says the app used "Material3 as is"; on `dev` at `ad15632` the
app depends on `org.jetbrains.compose.material:material` (Material 2:
`androidx.compose.material.*`, `MaterialTheme.colors`, `typography.h6`),
not Material3 (`desktop/app/build.gradle.kts`, the `import`s of `ui/*.kt`).
ADR-0133 also asks that "nothing a user sees changes" while the screens
move onto the design system (PLAN-0012).

What the screens repeat, read from `ui/*.kt`: a card over a dimmed window
with the same padding and gaps (11 dialogs, six widths), a row of buttons
at its end, text in six styles (`h6`, `subtitle2`, `body1`, `body2`,
`caption`, monospace) with `FontWeight.Medium` or the error color, a
checkbox with a clickable label, a dropdown "Choice", a pane title, a
placeholder, a selected list row, and `.dp` gaps of 2, 4, 6, 8, 12, 16
and 24.

## Options

1. **Wrap Material 2.** Components keep Material's rendering; the theme
   passes Material's default light colors, typography and shapes, so the
   screenshots stay as they are.
2. **Move to Material3 now.** Different default colors, type scale,
   button and field shapes: every screen changes look in a refactor
   plan, against ADR-0133's "no behavior change".
3. **Own components on `foundation` only.** No Material at all; most
   work, and every screen changes look.

For the rule: a Gradle-time check (detekt with custom rules, or Konsist)
needs a new tool and its configuration; a `grep` script over
`desktop/*/src/main` catches the four rules of ADR-0133 (raw color,
`MaterialTheme`, a Material import, a raw `.dp` in `padding`,
`spacedBy`, `Spacer` or `RoundedCornerShape`) in 0.05 s on this machine.

## Decision

Option 1, with the rule as `scripts/designsystem_check.sh`
(`make designsystem-check`, in `make check`) and its bash test in
`make scripts-test`.

- `desktop/designsystem` depends on Material with `implementation`, so
  no other module sees it on its compile classpath through the design
  system; `foundation` is `api`.
- Components take strings, not slots (`TextButton(label, onClick)`,
  `TextField(label = "…")`), and text takes a `TextRole` instead of a
  style, weight or font, so a screen cannot rebuild a look from parts.
- Spacing, radius, elevation and dialog widths are plain objects
  (`Spacing.lg`), not composition locals: they are the same in light
  and dark and are used inside non-composable `Modifier` chains. Colors
  that change with the theme go through `AppMonitorTheme.colors`.
- The run diff's dialog went from 1100 to 1180 dp, the benchmarks' width
  (`DialogWidth.Table`): the one screenshot change of the move.
- Sizes (`Modifier.width(240.dp)`, `heightIn`) stay in screens: the rule
  is about spacing and radius, as ADR-0133 states.

## Consequences

- Moving to Material3 later touches `desktop/designsystem` only; the
  screens keep their calls.
- The script is text matching: a `.dp` gap passed through a variable, or
  a Material call by its fully qualified name, slips through. The Gradle
  graph does not stop it either, since `desktop/app` still has Material
  at runtime.
- `make check` grows by one `grep` pass over the desktop sources and a
  bash test of nine cases: 0.05 s and 0.13 s on this machine (bash 3.2).
