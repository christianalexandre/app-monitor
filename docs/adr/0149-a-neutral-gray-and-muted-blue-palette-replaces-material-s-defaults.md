---
id: ADR-0149
title: A neutral gray and muted blue palette replaces Material's default colors
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-26
superseded_by:
---

# ADR-0149 — A neutral gray and muted blue palette replaces Material's default colors

## Context

ADR-0145 made every color a token in `AppColors` with a light and a dark
value, and kept Material 2's defaults as those values so no screenshot
changed: light primary `0xFF6200EE` (purple), secondary `0xFF03DAC6`
(teal, the checkbox's color), dark primary `0xFFBB86FC`, pure black text
on pure white. During the field test hold the owner asked for "a more
neutral and pleasant palette" instead of Android's default purple.

The reference (standing owner decision, `AGENTS.md`): `mibo-smart-app`'s
`core/ui/designsystem/.../theme/color/MiboPalette.kt` declares raw tonal
scales as objects (`MiboGray.gray50` … `gray900`, `MiboGreen`, `MiboRed`,
`MiboYellow`, `MiboBlue`), and `MiboColorScheme.kt` builds the light and
dark semantic schemes from those tones only. Its hues are Intelbras's
brand green, not a fit for a neutral tool.

## Options

1. **Tonal scales in `Palette.kt`, the two schemes built from them, a
   cool gray and one muted blue accent.** Mibo's structure; the hues of a
   developer tool where traffic, not chrome, carries the color.
2. **Keep Material's defaults.** What the owner asked to leave.
3. **Move to Material 3 with a generated tonal scheme.** Rejected in
   ADR-0134 for now; a different type scale and component set too.

## Decision

Option 1, following `MiboPalette.kt` and `MiboColorScheme.kt`.

- `desktop/designsystem/Palette.kt`: `internal` objects `AppGray`
  (`gray0` … `gray900`), `AppBlue`, `AppGreen`, `AppAmber`, `AppRed`;
  only `LightAppColors` and `DarkAppColors` read them.
- Light: white surface, `#23272E` text, primary and secondary `#3D6BD1`,
  error `#C2362B`, selection the primary at 12 %.
- Dark: `#1B1E23` surface, `#E2E5EA` text, primary and secondary
  `#86A6EE`, error `#F28B82`, selection the primary at 20 %.
- Secondary equals primary, so checkboxes match buttons and tabs instead
  of Material's teal. Badge tones keep their meaning (success green, info
  blue, warning amber, error red, neutral gray) on the new scales.
- `GalleryTest` reads the expected surface from the tokens, not a literal;
  `AppMonitorAppTest`'s dark window is below 0.2 luminance, not 0.1
  (`#1B1E23` is about 0.12).

## Consequences

- Every desktop screenshot changes once; `docs/screenshots/` from a real
  device keep the old colors until the next `liveScreenshots` run.
- A new color is a tone in `Palette.kt` first, then a token in
  `AppColors`; screens still never see either directly (ADR-0145).
- The values are a taste call: changing them later touches `Palette.kt`
  and the two schemes only.
