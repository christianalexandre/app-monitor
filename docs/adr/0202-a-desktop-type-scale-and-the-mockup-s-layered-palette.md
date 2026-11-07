---
id: ADR-0202
title: A desktop type scale and the mockup's layered palette replace Material's type and ADR-0149's values
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-06
superseded_by:
---

# ADR-0202 — A desktop type scale and the mockup's layered palette

## Context

PLAN-0018 (H8) brings the traffic window to the mockup the owner approved on
2026-11-06 (`docs/screenshots/h8-mockup.html`). Its first task is the type
and the tokens.

- `AppMonitorTheme` passed Material 2's `Typography()` (read in `Theme.kt`).
  That is the mobile scale: 16 sp `body1`, 14 sp `body2` and a `button`
  style with 1.25 sp letter spacing. The field test of 2026-11-06 found the
  window too large and sparse because of it.
- The mockup's tokens (the `L` and `D` objects in its script) give the
  sizes in px: row and sidebar text 12.5, buttons and code 11.5, badges and
  status bar 10.5–11, section labels 10, `uppercase`, `letterSpacing
  0.08em`, and a monospace stack for code. Its radii are mostly 6 (eight
  uses against four of 4), its bars 40 (title), 38 (detail) and 24 (status).
- Its colors are one neutral gray per layer: title bar `#F0F0F2`, sidebar
  `#EAEAEC`, list `#F6F6F8`, content `#FFFFFF`, status bar `#EAEAEC`, border
  `#D8D8DA` (dark: `#1C1C1E`, `#18181A`, `#1E1E20`, `#252527`, `#161618`,
  `#3A3A3C`). The accent `#4A6FA5` (dark `#6B9FD4`) marks only the
  selection and the active control; buttons are the secondary text color
  `#6E6E73` (dark `#8A8A8E`). Status tones: ok `#1F8A3B`, warning
  `#B26A00`, error `#C93A32`, info `#3A5F95` (dark `#4CD964`, `#FFD60A`,
  `#FF6961`, `#7AAFD8`).
- The reference (standing owner decision, `AGENTS.md`): the owner's
  reference app's typography object declares every style explicitly (`fontSize` and `lineHeight` in sp, `letterSpacing`
  in em) and keeps the line height untrimmed with
  `LineHeightStyle(…, Trim.None)` so stacked texts are spaced as designed.
  ADR-0149's palette structure (tonal scales in `Palette.kt`, two schemes
  built from them) also came from it and stays.

## Options

1. **Own scale per `TextRole`, Material's `Typography` slots filled from
   it, and the mockup's tones in `Palette.kt`.** The reference's way of declaring
   styles; Material's buttons, fields and menus read the same scale, so no
   component keeps a mobile size or a spaced label.
2. **Scale only `TextRole`, keep `Typography()` for Material's
   components.** Buttons and fields would stay at 14–16 sp with spaced
   labels, the defect the field test named.
3. **A `Typography(defaultFontFamily, …)` with Material's defaults shrunk by
   a factor.** Fewer lines, but no size matches the mockup and the letter
   spacing stays.

## Decision

Option 1, following the reference app's typography object.

- `Theme.kt` holds the scale, in sp with explicit line heights, no letter
  spacing but the label's: `Title` 14/18 semibold, `Heading` 13/17
  semibold, `Body` 12.5/17, `BodyStrong` 12.5/17 medium, `Small` 11.5/15,
  `SmallStrong` 11.5/15 medium, `Caption` 10.5/14, `Label` 10/13 semibold
  0.08 em (new: [`Text`] shows it in capitals and the `muted` color),
  `Code` 11.5/18 monospace, `CodeCaption` 10.5/14 monospace. Material's
  `Typography` is built from these slots (`button` is `Small`).
- `Palette.kt` holds the mockup's tones; ADR-0149's values are replaced.
  `AppColors` gains `titleBar`, `sidebar`, `list`, `statusBar`, `border` and
  `muted`. `primary` is the accent; `selected` is it at 10 % (dark 13 %).
  `Divider` paints `border`; `TextButton` paints `neutral`.
- `Radius.medium` (6 dp) for fields, chips, toasts, code blocks and
  Material's shapes; `Radius.small` (4 dp) for badges.
- `BarHeight.title` 40, `.detail` 38, `.status` 24 dp, for H8.3 and H8.4.
- The gallery shows every role, the layers and the bars; `TypeScaleTest`
  pins the sizes, the line heights and the letter spacing.

## Consequences

- Every desktop screenshot changes once; layouts do not (H8.2–H8.4 change
  them). The layers' colors are unused by screens until H8.4.
- ADR-0149 is superseded; its structure (tones read only by the schemes)
  holds, now under this ADR.
- Sizes are in sp and follow the platform's density, as before.
