---
id: ADR-0212
title: The detail opens on Body, and Settings draws a cog
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-07
superseded_by:
---

# ADR-0212 — The detail opens on Body, and Settings draws a cog

## Context

PLAN-0018 H8.12, the owner's second look at the window (2026-11-07):

- Picking a request opened its detail on Summary. ADR-0147 holds the tab in
  `TrafficViewModel`, starting at `DetailTab.Summary`, and keeps it across
  picks. What the owner reads first is the body.
- The title bar's Settings button "draws the light/dark icon". `AppIcons.Gear`
  (ADR-0209) was drawn from the mockup's `gear` SVG
  (`docs/screenshots/h8-mockup.html`): a circle of radius 2.5 and eight
  straight rays, which is the usual sun of a theme switch, not a gear.
- The other title bar buttons, read in `app.png` and `app-dark.png` enlarged:
  Save a bookmark, Export HAR an upload arrow, Issues a bug, Benchmarks bars,
  Compare runs two opposite arrows, Mock rules a mask, Breakpoints a pause,
  Clear and Record text buttons. Each matches its name.

Not reopened: the tabs, their order and the tab kept across picks (ADR-0147,
ADR-0204); the icon set and its stroke (ADR-0209).

## Options

1. **The view model's tab starts at Body; the cog redrawn in `AppIcons.Gear`.**
   One default changes; a tab the developer picks still stays. A connection
   layer exchange has no Body tab, so the detail falls back to Summary as it
   already does for any tab the exchange lacks. Every place that uses `Gear`
   (title bar, sidebar, palette) gets the cog.
2. **Body only on the very first pick of a session, then the last tab.** The
   same in use, since ADR-0147 already keeps the last tab: no difference worth
   a second state.
3. **A new `Cog` icon beside `Gear`, the sun kept.** Nothing uses a sun; a
   second icon named for the same thing would only invite the wrong pick.

## Decision

Option 1. `TrafficViewModel`'s tab starts at `DetailTab.Body`, narrowing
ADR-0147's first tab; the rest of ADR-0147 stands. `AppIcons.Gear` is an
eight-toothed cog (outer radius 6.4, root 4.8, teeth 22° wide at the tip)
around a hole of radius 2, drawn for this repository, in ADR-0209's stroke.

## Consequences

- App tests that read Summary first open it with `showTab("Summary")`;
  `assertTabOpen` checks the tab a first pick opens.
- The gallery's `Gear` cell and every screenshot with the title bar or the
  sidebar's gear change.
- The mockup's `gear` SVG still draws the sun: the mockup is a reference, not
  the source of truth for an icon that reads wrong.
