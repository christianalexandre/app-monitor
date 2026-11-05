---
id: ADR-0173
title: The SDKs' log lines are readable outside a debugger
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0173 — The SDKs' log lines are readable outside a debugger

## Context

PLAN-0015 H5.R2. The SDKs' only channel to a developer whose device shows
nothing is one log line (README, *Nothing in the desktop?*; SDD 04, SDD 06).
The Swift SDK wrote it with `Logger.error("\(line)")`, and `Logger` treats an
interpolated string as private by default.

Measured on this Mac, one test process writing both forms, read with
`log show`:

```
[AppMonitor:probe] old: <private>
[AppMonitor:sdk] new: probe-dynamic-2
```

The simulator does not redact: a Debug sample launched by `simctl`, without a
debugger, showed the old form in clear too. So the simulator cannot tell the
two apart, and macOS is the evidence for the redaction.

What goes through this line: the desktop's host and port, the system's error
text, names of Info.plist keys. No traffic.

The second finding of the same task: `sdk/core`'s iOS `platformLog` called
`NSLog("AppMonitor: %@", message)`, and that vararg call ended the test
process with signal 11 on the simulator (Kotlin 2.4.21), on the plain message
tried. Its tests had always passed a `log` of their own, so none reached it.
A device was not tried.

## Options

1. **`privacy: .public` in one `sdkLog` function, used by every SDK line.**
   The line is readable in Console and the syslog. Costs the desktop's LAN
   address in the device's log, in Debug builds only.
2. **`.public` on the static reason, private on host and port.** The reason
   is readable, but which desktop was refused or unreachable is not, and that
   is what the developer needs to act.
3. **Keep it private.** The line is `<private>` on every real device unless a
   debugger is attached, so the developer has no reason at all.

## Decision

Option 1, under the standing owner decision *Debug only* (`AGENTS.md`,
ADR-0072, ADR-0155): release builds hold no SDK code, so these lines exist
only in a developer's debug build.

`sdk/core`'s iOS `platformLog` drops the vararg: the message is escaped
(`%` to `%%`) and sent as the format.

## Consequences

- The line names the desktop's LAN address in the device's log.
- `PlatformIosTest` writes a line through `platformLog` on the simulator; a
  crash there fails the suite. A test with a pinned device must pass its own
  `log`: Android's `Log.w` throws in host tests.
- A physical iPhone was not run, none being connected: the readable line is
  shown on the simulator, the redaction it avoids on macOS (DEBT-0121).
