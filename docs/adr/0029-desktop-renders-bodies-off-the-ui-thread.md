---
id: ADR-0029
title: The detail pane is a lazy list; bodies render off the UI thread in chunks
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0029 — The detail pane is a lazy list; bodies render off the UI thread in chunks

## Context

PLAN-0002 M1.10 pays DEBT-0006 (body formatting runs in composition, on the
UI thread, and the whole result is one `Text`) and DEBT-0003 (WebSocket
frames are dropped). Bodies on the wire are at most `Protocol.MAX_BODY_BYTES`
(1 MiB, ADR-0018) inside a frame of at most `MAX_FRAME_BYTES` (ADR-0021).

Evidence, checked on 2026-10-22:

- `BodyFormatTest`: JSON and hex rendering are linear in the body, but
  indentation grows with depth: a 1 MiB array of `1`s nested 400 levels deep
  is 524 288 lines of 1 600 spaces each, over 800 M characters uncapped.
- kotlinx-serialization-json 1.12.0 rejects input nested 500 levels deep
  ("Json input is too nested…") and accepts 300 (measured in a scratch test),
  so indentation per line stays under ~2 000 characters.
- `AppMonitorAppTest`: with rendering moved back into the composition
  behind a 3 s delay, `a body at the protocol limit renders off the UI
  thread` fails; with this design it passes.

## Options

1. **Keep one `Text` per body, render in a coroutine** — the UI thread no
   longer formats, but still lays out millions of characters at once.
2. **Detail pane as a `LazyColumn`, body rendered on `Dispatchers.Default`
   and split into chunks** of at most 100 lines or 8 192 characters, one
   item each — layout cost is bounded by what is visible. Selection across
   chunks that scrolled away is lost.
3. **Cap the shown body at N characters** — bounded and simple, but hides
   bytes the device sent.

## Decision

Option 2, plus a cap of 16 Mi characters on indented JSON only (the one
format whose output is not linear in the body). Frames are listed under their
exchange in the same lazy list, one row each (direction, opcode, size, time
since the request started, a one-line preview of at most 512 bytes); one
frame at a time opens with the same body view and format buttons.

## Consequences

- Each body shows "Rendering…" until its rendering arrives; UI tests wait
  for text instead of asserting it at once.
- `DecodedBody` is thread-safe: two formats can render at once.
- Frames are appended to the exchange by copying its list, like exchanges
  themselves (DEBT-0004).
