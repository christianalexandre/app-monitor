---
id: ADR-0031
title: End to end evidence from an offscreen render fed by a real device, by hand until M3
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0031 — End to end evidence from an offscreen render fed by a real device, by hand until M3

## Context

PLAN-0002 M1.12 asks for screenshots of the desktop showing the sample's
traffic from the emulator (`10.0.2.2`) and from a physical device (mDNS), and
to decide whether the emulator run becomes a `make` target now or in M3
(DEBT-0002's trigger). M3's roadmap already lists "Android e2e: sample app on
an emulator talking to a headless desktop server".

Evidence, 2026-10-23, on the owner's Mac:

- `screencapture -x` from the agent's shell failed with "could not create
  image from display": the process has no Screen Recording permission, so a
  screenshot of the real window, or a click on its Approve button, needs a
  permission only the owner can grant.
- The window in `Main.kt` renders `AppMonitorApp(state, status, pending,
  approvals::decide)`; the offscreen UI tests already render and click that
  composable (`runDesktopComposeUiTest`, ADR-0011).
- Runs of option 1 below: Samsung SM-G975F (Android 12) over Wi-Fi via mDNS,
  and the `Medium_Phone` emulator (Android 17) with mDNS advertising off, so
  through `10.0.2.2`. Each reached approval, then 5 finished exchanges, in
  about a minute (`BUILD SUCCESSFUL in 59s` / `57s`).

## Options

1. **Opt-in Gradle test task** `liveScreenshots`: real server, real
   `DeviceApprovals`, the window's composable offscreen; the test clicks
   Approve and writes the screenshots. Runs anywhere the unit tests run; does
   not show the OS window frame.
2. **Screenshot the real window** with `screencapture` and click with
   accessibility scripting. Exact pixels, but needs two macOS permissions and
   is macOS-only.
3. **A `make` target that boots the emulator and taps the buttons now.**
   Fully automatic, but the buttons call public services and a cold emulator
   boot takes minutes: neither belongs in `make check`, and the M3 release
   gate is where it would run.

## Decision

Option 1, excluded from `test` and from `make check`; the sample is driven by
hand with `adb` (SDD 02). The automated emulator run is left to M3 (option
3), as in the roadmap; DEBT-0002's trigger does not fire, since `make check`
gains no emulator suite.

## Consequences

- `make check` is unchanged; `liveScreenshots` costs nothing unless run.
- The screenshots are the window's content without its title bar.
- The run needs internet, a free port 47474 and a person (or agent) to tap
  the buttons until M3 automates it.
