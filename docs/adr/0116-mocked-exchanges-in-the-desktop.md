---
id: ADR-0116
title: How the desktop keeps and shows a mocked exchange, and the headless server's rules
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0116 — How the desktop keeps and shows a mocked exchange, and the headless server's rules

## Context

ADR-0110 E.1 decided that a mocked exchange is captured as usual with
`RequestStarted.mock_rule_id`, and that the desktop lists it with a
"mocked" mark and the rule's name. ADR-0115 made OkHttp and Ktor send that
field. Left open, inside the agreed architecture (`AGENTS.md`): where the
store keeps it, what the window and MCP show when the rule is gone, and
how an end to end gives a device a rule without the window (PLAN-0010
M9.5).

Evidence, 2026-10-25:

- `mock_rules.id` is `AUTOINCREMENT`, so a deleted rule's id is never
  given to another rule (`Mock.sq`, ADR-0113).
- The request list reads `StoredExchange` rows with no join (SDD 09); the
  detail reads one exchange by id (`Storage.exchange`).
- `desktop/server`'s `main` has no store and no window: it logs
  envelopes, and the end to end scripts assert on that log
  (`scripts/e2e_lib.sh`, SDD 02). `DeviceSession.send` queues an envelope
  for a device that accepted its channel (SDD 12).

## Options

### A. Where the id is kept

1. **A column `exchanges.mock_rule_id`** (migration `4.sqm`), set from
   `RequestStarted`, read by every list query. The rule's name is read
   from `mock_rules` in the detail only. A deleted rule leaves the id with
   no name.
2. **The rule's name copied into the exchange.** The name then survives
   a deletion, but a renamed rule shows its old name, and the device would
   have to send a name it does not have.
3. **Read only from `request_meta`.** No migration, but the list would
   decode every row's request blob to mark it.

Chosen: 1. A deleted rule shows as `Mocked by deleted rule <id>` and
`mock_rule_name: null`.

### B. How the headless server gets rules

1. **`--mocks FILE`**, a JSON file named as `interaction.proto`'s fields,
   sent once to each session that accepted `interaction`, with
   `kotlinx-serialization-json` (Apache-2.0, already in `desktop/app` and
   `desktop/mcp`). A mocked `RequestStarted` is logged with ` mock <id>`,
   which `e2e_lib.sh` asserts as `mock:<id>`.
2. **A store behind the server**, rules made through `desktop/storage`.
   It would bring the store into a tool that has none, for one test rule.
3. **The binary `MockRules` in a file.** No new dependency, but a script
   could not write it.

Chosen: 1.

## Decision

A.1 and B.1.

## Consequences

- Every list reader, the window and MCP get `mock_rule_id` for free; the
  name costs one lookup per detail.
- `desktop/server` depends on `kotlinx-serialization-json`.
- The Android sample gains a last button, `418, or a mock`, that the end
  to end mocks. A rule only reaches the device once it is connected, so the
  mocked call is the last of `runAll`.
- The headless server sends its rules once per session; changing them
  needs a restart.
