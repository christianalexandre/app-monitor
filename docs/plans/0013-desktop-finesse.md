---
id: PLAN-0013
title: Desktop finesse — live traffic, recordings, themes and layout
status: Done # Draft | Active | Done | Abandoned
milestone: H3
started: 2026-10-25
finished: 2026-10-25
---

# PLAN-0013 — Desktop finesse (H3)

## Goal

The desktop works the way the owner expects after the first field test
(ADR-0140): live traffic of every device with Clear by default, runs as
recordings that hold every device and compare them, a device can be
disconnected, light and dark themes, and a layout polished after
`app-network-monitor`'s macOS client and RocketSim.

## Tasks

Built on ADR-0133's modules: each task changes `desktop/domain`,
`desktop/data` and the feature it touches, never a screen reaching the
store. Every UI task adds or updates screenshots in light and dark. A
task too large for one PR is split at once.

- [x] **H3.1 Live list source.** `LiveTraffic` holds every device's
  exchanges in memory (bounded), with Clear; `desktop/data` serves it and
  the store behind the existing repositories (ADR-0141). Split from the
  original H3.1, which was too large for one PR.
  Accept: `./gradlew :desktop:storage:test :desktop:data:test
  :desktop:mcp:test` — traffic from two devices shows in one list, Clear
  empties it, the bound drops the oldest, the store gets no row of it,
  MCP tools read it when no run is given.
- [x] **H3.1b Live list in the window and Clear.** The device server and
  the proxy feed the live list, not the store; the window opens on it
  with Clear and "All devices"; MCP reads it by default. "No run opens at
  launch" moved to H3.2, where Record opens runs; "New run" is gone until
  Record.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test
  :desktop:storage:test` — traffic from two devices shows in one list,
  Clear empties it, the store gets no exchange row while nothing is
  recording.
- [x] **H3.2 Record and Stop.** Record / Stop: every exchange that starts
  while recording is stored in the run, whatever its session (`Recorder`,
  ADR-0142); no run opens at launch; existing runs stay as they are (no
  migration needed: they are already rows of `runs`); `liveScreenshots`
  gets its reopen step back on a recording. Split from the original H3.2,
  which was too large for one PR.
  Accept: storage, data and traffic tests — a device connected before
  Record and one that reconnects during it are both in the run; after
  Stop new traffic is only in memory.
- [x] **H3.2b Save, and runs and "Saved" apart.** Save on selected live
  exchanges into a "Saved" collection (a migration marks it apart from
  recordings); runs and "Saved" listed apart and opened read-only; an
  issue saves its exchanges (until then "Save as issue" on live requests
  is refused).
  Accept: storage, data and traffic tests — a saved exchange survives a
  restart; an issue made from live requests keeps them. Built as one run
  marked `saved` (ADR-0143); MCP's `save_issue` saves live ids too.
- [x] **H3.3 Compare devices in a run.** Endpoint by device columns
  (count, status classes, median, p95, size) in `insights`.
  Accept: `./gradlew :desktop:feature:insights:test` — a run with two
  devices shows both columns with their numbers.
- [x] **H3.4 Disconnect and reconnect a device.** ADR-0140 E.
  Accept: `./gradlew :desktop:server:test :desktop:feature:traffic:test`
  — a disconnected device's reconnects are refused without a prompt
  until Reconnect. Held in memory (ADR-0144); disconnected devices get
  their own "Disconnected" section, since a session without exchanges
  leaves the live list; screenshot in light only until H3.5.
- [x] **H3.5 Light and dark.** Full token set with both values, System /
  Light / Dark in the settings.
  Accept: `make check` (the design system script) and the gallery and
  screen screenshots in both themes. Stored as `ui.theme` (ADR-0145);
  every `desktop/app` screenshot has a `-dark.png` twin; the script also
  refuses named colors; `liveScreenshots` stays light.
- [x] **H3.6 Toolbar, request row and empty states.**
  ADR-0140 G: one toolbar over the three panes (runs, Record/Stop,
  Clear, Save, search, filters, the other screens); a request row shows
  a status badge colored by class, method, URL truncated in the middle,
  start time and duration; empty states say what to do next. Split from
  the original H3.6, which was too large for one PR.
  Accept: `./gradlew :desktop:designsystem:test :desktop:app:test` —
  the gallery shows the badge tones and the toolbar in both themes; the
  live, filters and empty-list screenshots in light and dark. The links
  to other screens moved under the devices (no room at 1280 px); the URL
  is cut by a measured `MiddleEllipsisText`, since Compose Desktop draws
  `TextOverflow.MiddleEllipsis` at the end (ADR-0146).
- [x] **H3.6b Detail tabs.** Summary, Headers, Body, Timing, cURL, each
  with its copy action.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test` —
  each tab shows its part of a request and copies it. `Tabs` in the
  design system after `MiboTabs`; the tab stays across picks; a body is
  copied as the device sent it; Timing has start, end, duration and
  frames only, the protocol carries no phase (ADR-0147).
- [x] **H3.6c Filter sheet.** Status class and host, as
  `app-network-monitor`'s sheet, replacing the toolbar's filter choices.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test` —
  a host filter keeps only that host's rows; screenshots of every
  screen in light and dark listed in the PR for the owner's review. The filter
  keeps sets of status classes and hosts, empty keeps all; the hosts come
  from the store or the live list; method and "Failed only" moved into
  the sheet too, the toolbar keeps the search (ADR-0148).

- [x] **H3.R1 A run's end stays put.** Review findings 1 and 2: Record
  after Stop, and `close`, ended the current run again, so a stopped
  recording's end moved to the next Record or to quitting. `end` only
  sets a run's end while it has none.
  Accept: `./gradlew :desktop:storage:test` — record, stop, record,
  stop, close: each recording keeps the end its Stop gave it.

### H3 milestone review

`code-review` at `medium` of `d4ccd48..d02b21e` (#275 to #285). Ten
findings, two that normal use hits:

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | `NewRun` ends the current run without a condition, so Record after Stop moves the stopped recording's end to the new Record. | Yes: Record, Stop, Record | H3.R1 |
| 2 | `close` queues an `EndRun` while nothing records, moving the last recording's end to the moment the app quits. | Yes: Stop, then quit | H3.R1 |
| 3 | `LiveTraffic.trim` drops an exchange still open, and its later frames make a blank row. | No: needs the live bound reached while a socket is open | DEBT-0093 |
| 4 | An exchange saved before its response stays without it, and a second Save is skipped. | No: the response must still be pending when Save is pressed | DEBT-0093 |
| 5 | Every envelope wakes the live observers, which rescan up to 10k rows. | No: cost, not wrong data | DEBT-0093 |
| 6 | The detail's app id is a snapshot of the sessions when picked. | No: needs a pick before the sessions load | DEBT-0093 |
| 7 | Clear keeps marks on ids of sessions no longer shown. | No: needs a trimmed session's marks | DEBT-0093 |
| 8 | `Recorder` keeps one entry per recorded request until the session ends. | No: needs a session recorded for hours | DEBT-0093 |
| 9 | Save counts stored marks carried into Live as failures. | No: needs marks made in "Saved" kept into Live | DEBT-0093 |
| 10 | Three KDoc blocks are over four lines. | No | DEBT-0093 |

## Outcome

- **Shipped**:
  - Plan (#274), and ADR-0140, what the desktop stores (#275).
  - H3.1: the live list in memory behind the repositories (#276).
  - H3.1b: the window opens on the live list, with Clear (#277).
  - H3.2: Record and Stop (#278).
  - H3.2b: Save into "Saved", apart from recordings (#279).
  - H3.3: a run's devices compared per endpoint in benchmarks (#280).
  - H3.4: disconnect a device until Reconnect (#281).
  - H3.5: light and dark themes, chosen in the settings (#282).
  - H3.6: the toolbar over the panes, request rows with a status badge
    (#283).
  - H3.6b: request detail in tabs, each with its copy action (#284).
  - H3.6c: the filter sheet by status class and host (#285), with the
    storage tests bounded in time and CI's test reports kept on cancel.
  - H3.R1: a run's end stays put, and this close (this PR).
- **Decisions**: ADR-0140 (only recordings and saved exchanges are
  stored), ADR-0141 (the live list as the store's rows in memory),
  ADR-0142 (a recorder sink), ADR-0143 (Save into one saved run),
  ADR-0144 (disconnect in memory until Reconnect), ADR-0145 (the theme as
  a stored setting), ADR-0146 (toolbar and status badge), ADR-0147
  (detail tabs), ADR-0148 (filter sheet).
- **Debt**:
  - Paid: DEBT-0088's one query per replay row (#276).
  - Fired and kept: DEBT-0067 (#278, #279), its code unchanged.
  - Re-triggered: DEBT-0091 (#285), after a
    second CI hang of `:desktop:storage:test`, now to a CI
    `TimeoutException` with its stack kept.
  - Created: DEBT-0093 (this review's findings 3 to 10).
  - No correctness finding that normal use hits is left open.
- **Deviations**:
  - Split for size: H3.1 into H3.1 and H3.1b, H3.2 into H3.2 and H3.2b,
    H3.6 into H3.6, H3.6b and H3.6c.
  - "No run opens at launch" moved from H3.1b to H3.2; the links to
    other screens moved under the devices (no room at 1280 px).
  - H3.6c fixed two CI test races on the way (`SaveTest`'s clock and
    `ApprovalStoreTest`'s queued decision).
  - H3.R1 added by the milestone review.
- **Lessons**:
  - `TestExceptionFormat.SHORT` hid which assertion failed. Uploading
    `build/test-results/` on failure and cancel gave the line and values
    of a one-off CI failure; it covers every suite and should stay.
  - A test that reads a `Flow`'s first value right after a queued write
    races the writer; wait for the value instead (`first { … }`).
