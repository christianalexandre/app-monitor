---
id: ADR-0179
title: A write committed as the store closes is answered saved
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0179 — A write committed as the store closes is answered saved

## Context

`McpServer`'s wrapper replaced any tool answer with "App Monitor is closing" when
`isClosing` was true after the call, since a closing store reads as empty (SDD 10). A
`save_issue` or `create_mock` committed just before the store started closing was therefore
reported as failed, and a retry after the restart stored it twice (DEBT-0074).
`IssuesTest.aSaveCommittedAsTheStoreClosesIsAnsweredSaved` reproduces it: the store closed
right after the commit, the client got the closing error. A tool also reads its write back,
which a closing store answers with nothing.

The window's exports had no check at all: `ExportHar.log` read pages until the store emptied
them and reported success on a partial file (`Exports`).

## Options

1. **Keep the wrapper's rule**: one shape of answer and one sentence, but an agent that
   retries after the restart stores the issue or the rule twice.
2. **The rule by kind of call**: a read's answer is replaced, a write's stands, and a write
   that cannot be read back answers its id and a `note`. One more shape of answer, on a
   shutdown race only.
3. **Hold the close until tools return**: the store would wait on MCP clients, against
   `Storage.close`'s bound.

## Decision

Option 2. `closingChecked` in `ToolResults.kt` is the one place: a refusal is replaced while
the store closes, an answer unless it stands. The wrapper lets stand what is not
`readOnlyHint`; `Exports` keeps nothing, so a window's export is refused.

## Consequences

- An agent is never told a committed write failed; `replay_request`, sent to the device by
  then, is told the same.
- A write answered this way carries `{"issue": {"id": N}, "note": …}` (`"mock"` for a rule)
  instead of the whole row; nothing else changes shape.
- Pays DEBT-0074.
