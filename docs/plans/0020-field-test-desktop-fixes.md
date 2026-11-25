---
id: PLAN-0020
title: What the 0.1.0 field test asks of the desktop
status: Active # Draft | Active | Done | Abandoned
milestone: H9
started: 2026-11-15
finished:
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

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
