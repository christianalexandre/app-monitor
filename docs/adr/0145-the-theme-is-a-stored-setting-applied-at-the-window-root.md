---
id: ADR-0145
title: The theme is a stored setting applied at the window root, every color a token with two values
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0145 — The theme is a stored setting applied at the window root, every color a token with two values

## Context

ADR-0140 F asks for a theme that follows the system, a System / Light /
Dark setting, every color a token with both values in
`desktop/designsystem`, and screenshots in both (PLAN-0013 H3.5).

On `dev` at `4fa8e98`, `AppMonitorTheme(dark)` already switched Material's
`lightColors()` / `darkColors()` and two own colors (`selected`, `scrim`),
but nothing passed `dark = true` outside the gallery, and no screen paints
a color of its own (`grep` for `graphics.Color` outside the design system
finds nothing).

Evidence read:

- Compose Multiplatform 1.12.2's `foundation-desktop` has
  `isSystemInDarkTheme()` (`DarkThemeKt`, delegating to
  `DarkTheme_skikoKt`), and `ui-desktop` provides it from the OS
  (`ProvideSystemTheme_desktopKt`, with a poll of the current theme): `javap`
  on the jars in the Gradle cache.
- Material 2's defaults, read with `javap -c` on `ColorsKt` of
  `material-desktop-1.12.2.jar`: light primary `0xFF6200EE`, primary variant
  `0xFF3700B3`, secondary `0xFF03DAC6`, secondary variant `0xFF018786`, error
  `0xFFB00020`; dark primary `0xFFBB86FC`, background and surface
  `0xFF121212`, error `0xFFCF6679`.
- The reference (standing owner decision, `AGENTS.md`): `mibo-smart-app`'s
  `core/ui/designsystem/.../theme/MiboTheme.kt` takes
  `darkTheme: Boolean = isSystemInDarkTheme()` and provides one of two
  color schemes, `LightMiboColorScheme` or `DarkMiboColorScheme`, declared
  in `theme/color/MiboColorScheme.kt` as one data class of semantic colors.

## Options

1. **The mode in the store's `settings`, read once at launch into a
   `StateFlow`, applied by the window root.** Same pattern as the MCP token
   (`McpAccess`: read at open, in memory at once, written through the
   queue). The design system stays a pure `dark: Boolean` switch.
2. **The mode inside the design system as a composition local.** The
   design system would read the store or need a holder of its own; it
   depends on nothing but Compose today (ADR-0134).
3. **Follow the system only, no setting.** Against ADR-0140 F.

## Decision

Option 1, following `mibo-smart-app`'s `MiboTheme.kt`: one `AppColors`
data class with every color a component paints and its Light and Dark
values, Material's `Colors` built from it.

- `desktop/domain`: `ThemeMode` (System, Light, Dark) and
  `AppearanceRepository` (`theme: StateFlow<ThemeMode>`, `setTheme`).
- `desktop/data`: `SettingRepository.appearanceRepository()` reads
  `ui.theme` (`system`, `light`, `dark`; unset or unreadable is System).
- `desktop/app`: `openServices` opens it beside the MCP token; the window
  root maps System to `isSystemInDarkTheme()`; "Clear all data" writes the
  current choice back, so a theme is not data that clearing removes.
  Without a repository (tests that pass none) the window is light.
- `desktop/feature/settings`: an "Appearance" section with a Theme choice.
- The light values are Material 2's defaults, so every light screenshot
  stays as it was; the dark ones are Material's dark defaults.
- `scripts/designsystem_check.sh` also refuses a named color
  (`Color.White`…) outside the design system.
- App screenshots are taken in both themes: the tests pass one shared
  `TestAppearance`, and `screenshot("x.png")` writes `x.png` and
  `x-dark.png`.

## Consequences

- A screen that wants a new color adds a token with two values; the
  script stops a raw or named one.
- `StorageUnavailable` follows the system: the store, and the setting,
  did not open.
- `desktop/app`'s tests write twice as many screenshots (24 more PNGs,
  each after one more recomposition).
- `liveScreenshots` (ADR-0031) stays light: it writes the README's
  pictures from a real device by hand.
- Brand colors stay Material's purple and teal; H3.6's layout pass may
  change token values without touching a screen.
