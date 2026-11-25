---
id: PLAN-0020
title: What the 0.1.0 field test asks of the desktop
status: Done # Draft | Active | Done | Abandoned
milestone: H9
started: 2026-11-15
finished: 2026-11-25
---

# PLAN-0020 — What the 0.1.0 field test asks of the desktop (H9)

## Goal

The owner used the desktop with the field test app on `0.1.0`, both
installed from the published coordinates (2026-11-15). This plan fixes the
four things that use asked for. All of them are in the desktop: no SDK
changes, so nothing waits for a `0.1.1`.

Owner decisions this plan follows (2026-11-15):

- **The newest request on top.** The live list puts each new exchange at the
  top, not the bottom. ADR-0139 A said "newest last".
- **Play and stop for the monitor and for mocks, as app-network-monitor
  does.** That app has a status dot with ▶/■ for its server and another for
  mocking, in the toolbar. Here, the monitor's stop **pauses the list**: the
  devices stay connected, and while paused the desktop shows and stores
  nothing new. Play resumes from that moment, with no backlog. Mocks and
  breakpoints keep working. Unlike app-network-monitor, the server is not
  closed, because the SDKs would resend what they buffered.
  - The mock switch is global. Off clears the rules on every device but
    keeps them, and each rule's own switch, in the desktop. On sends the
    enabled rules again.
- **A cut JSON value opens with a click.** ADR-0208 cuts a string value to
  200 characters with `… (N more)`. Clicking that value shows it whole, with
  wrapping. Clicking again folds it.

## Tasks

- [x] **H9.1 The newest exchange on top.** (`feat/newest-exchange-on-top`) The live list, a recording and a
  saved run list newest first. When the list is scrolled away from the top,
  a new exchange does not move what the developer is reading. The ⌘K
  palette, the search's next and previous, and the keyboard follow the new
  order. ADR for the change to ADR-0139 A.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with a
  test that a new exchange lands first, and the `live.png` screenshot with
  the newest on top.
- [x] **H9.2 Pause and resume the monitor.** (`feat/pause-the-monitor`) A status dot ("Live" /
  "Paused") with ▶/⏸ in the title bar's tools. While paused, exchanges from
  every device are neither listed nor stored, a recording included.
  Sessions, approvals, mocks and breakpoints keep working. Resuming starts
  from the next exchange. The pause lasts until the app quits.
  Accept: `:desktop:app:test` with a stand-in device: exchanges sent while
  paused never appear, those after resume do; the `paused.png`
  screenshot.
- [x] **H9.3 Mocking on and off.** (`feat/mocking-on-off`) A status dot ("Mocking" / "Not mocking")
  with ▶/■. Off sends an empty rule set to every connected device and to new
  sessions. On sends the enabled rules. The rule list and each rule's switch
  are kept, and the state is kept across restarts. ADR for where the switch
  lives (settings) and what an MCP `create_mock` does while it is off.
  Accept: `:desktop:app:test`: rules pushed to a stand-in device turn off and
  back on; the title bar's switch both ways. The switch lives in the traffic
  view model beside the monitor's, not in the mocks feature.
- [x] **H9.4 A cut value opens with a click.** (`feat/open-cut-json-value`) In the JSON view, clicking a
  value cut by ADR-0208 shows it whole, wrapped, and clicking again folds it.
  Search counts and walks an opened value as shown text. Copy, Text and Hex
  are unchanged.
  Accept: `:desktop:feature:traffic:test` and `:desktop:app:test`, with a
  value opened, searched and folded; the `detail-body.png` screenshot with
  one value opened.

## Outcome

### H9 milestone review

`code-review` at `medium` of `ce4ea7b..894fb65` (#426 to #429): a static read of the diff, 48
files, no Gradle test run by it. Eight findings, none that normal use hits, each then read in
the code here. Findings 1 to 3 are correctness findings left open, so this PR waits for the
owner.

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | `FollowNewest` takes a list scrolled exactly `arrived` rows down as at the top, if its effect runs before the layout, and jumps. | No: needs that timing and an offset of exactly 0 | DEBT-0137 |
| 2 | A replay's "Open" picks the latest of two replays that arrive in one store read, since the list is newest first. | No: two replays inside one read | DEBT-0137 |
| 3 | The cut values' links keep the `toggle` of a dead `opened` state when it is recreated with equal spans. | No: needs a click on the format already shown; another request recreates the detail (`key(picked)`) | DEBT-0137 |
| 4 | The pause filter decodes every network envelope to read its request id, paused or not. | No: a cost, not measured | DEBT-0137 |
| 5 | Breakpoints and replays see envelopes the pause skips: a held call without a row, a replay whose "Open" never comes. | No: needs a pause between asking and receiving | DEBT-0137 |
| 6 | A click inside an opened value folds it, so a click to select there is lost. | No: a selection gesture; Copy keeps the whole body | DEBT-0137 |
| 7 | Recording and paused, the empty list says "Paused" only. | No: a caption | DEBT-0137 |
| 8 | A `TrafficModule.kt` KDoc line over the line length. | Rejected: `make check`'s `spotlessCheck` passed it | — |

- **Shipped**: H9.1 the newest request on top (#426). H9.2 the monitor paused and resumed from
  the title bar (#427). H9.3 mocking switched off and on for every device (#428). H9.4 a cut
  JSON value opened and folded with a click (#429). Plan PR #425.
- **Decisions**: ADR-0221 to ADR-0224.
- **Debt**: Created DEBT-0137 (this review). DEBT-0131 notes the opened values; DEBT-0127 and
  DEBT-0133 re-triggered by H9.4. None paid.
- **Deviations**: H9.3's switch lives in the traffic view model beside the monitor's, so its
  test is the app's UI test, not `:desktop:feature:mocks:test`. No task added, split or dropped.
  After H9.4 the owner weighed freeing ADR-0168's pin (a device on several desktops) and kept it
  (2026-11-25).
- **Lessons**: all four asks came from the owner using the desktop on a real app, and each
  fitted one PR with no SDK change; the next field test findings can be planned the same way,
  desktop and SDK apart, so a desktop fix never waits on a library release.
