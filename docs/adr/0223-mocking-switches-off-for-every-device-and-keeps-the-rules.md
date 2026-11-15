---
id: ADR-0223
title: Mocking switches off for every device and keeps the rules
status: Accepted
date: 2026-11-15
superseded_by:
---

# ADR-0223 — Mocking switches off for every device and keeps the rules

## Context

PLAN-0020 H9.3. In the field test of `0.1.0`, the owner asked for a play
and stop for mocks, as app-network-monitor's client has (2026-11-15). There,
`toggleMocking` clears every rule on the devices when switched off and sends
the enabled ones again when switched on; the rule list and each rule's own
switch stay.

Here, `pushMocks` (`desktop/app`) sends each session that accepted
`interaction` its app's active rules (`observeActiveMocks`: enabled and
confirmed), again after each change (SDD 12, ADR-0113). An empty
`MockRules` replaces whatever rules the device holds.

## Options

1. **A stored setting, read by the push.** `mocks.enabled` in `settings`, on
   by default. The push combines it with the active rules and sends an empty
   set while it is off. No SDK change.
2. **Disable every rule.** This loses each rule's own switch, which
   app-network-monitor keeps.
3. **In memory only.** A restart would turn mocks back on without the
   developer seeing it.

## Decision

Option 1.
- `Storage.observeMocking` reads `mocks.enabled`: anything but `false` is
  on.
- `MockRepository.observeMocking` and `setMocking` wrap it. The interface
  gives them defaults (on, no-op), so the stand-ins in tests are unchanged.
- The title bar shows "Mocking" (a warning-colored dot, ■) or "Not mocking"
  (a neutral dot, ▶), beside the monitor's switch (ADR-0222).
- "Clear all data" deletes the setting with the rest, so mocking is on
  again after it.

## Consequences

- Switched off, a device answers no call from a rule until the switch is
  back on. A rule `create_mock` makes while off waits for its confirmation
  as before, then waits for the switch.
- The MCP tools do not read or change the switch.
