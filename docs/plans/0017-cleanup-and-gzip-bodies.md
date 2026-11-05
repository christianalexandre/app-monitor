---
id: PLAN-0017
title: The sweep's last findings and gzip bodies
status: Done # Draft | Active | Done | Abandoned
milestone: H7
started: 2026-11-04
finished: 2026-11-04
---

# PLAN-0017 — The sweep's last findings and gzip bodies (H7)

## Goal

Before the owner's release to `main` and field test, DEBT-0126 and DEBT-0050 are paid or
dropped with their reasons, and a gzip body opens in the window. The owner asked for this
plan on 2026-11-04, and picked gzip alone from the roadmap's *Later — readable bodies*.
The roadmap's `Hold` stays: M10 is still the owner's go.

## Tasks

- [x] **H7.1 The proxy's review findings.** (#374) A WebSocket message ending in a `BFINAL=1` block
  no longer leaves every later message captured empty (DEBT-0126, item 2, RFC 7692 7.2.3.4);
  a leaf that cannot be built in `TunnelTls`' asynchronous callback fails the tunnel instead
  of leaving it hanging (item 1); a plain tunnel closed without a byte is not labelled a TLS
  failure (item 3).
  Accept: `./gradlew :desktop:proxy:test`, a test for each item failing without its fix.
  Item 1 reproduced through the proxy: a scoped IPv6 host has no certificate name.
- [x] **H7.2 The rest of DEBT-0126.** (#375) The data directory lock survives a refused second open
  inside one JVM (item 4); `scripts/e2e_android.sh`'s `grep -q` under `pipefail`, and
  `accelerometer_rotation` restored on the emulator (item 5); the 16 KDocs over guideline 02's
  4 lines (item 6). DEBT-0126 paid.
  Deviation: two blocks the debt did not list, `LiveDeviceScreenshots.kt` and `CorpusTest.kt`, were
  over the limit too and went with it.
  Accept: `./gradlew :desktop:storage:test`; `make check`; `make e2e-android`.
- [x] **H7.3 DEBT-0050.** (#376) Its two `pr-conventions.yml` items read against today's
  `scripts/trailer_check.sh` and `origin/main..origin/dev`, each paid or dropped with that
  evidence; `scripts/debt_check.sh`'s basename and short path items paid or dropped with a
  case from this repository.
  Accept: `make scripts-test`; `make debt-check`.
- [x] **H7.4 Gzip bodies open in the window.** (#377) A request or response body whose
  `Content-Encoding` is `gzip` is opened before the detail's Text, JSON and Hex formats, with
  a bound on what it inflates to, and a cut or broken stream shows what could be read and
  says so. Only what the window shows changes: the store, the MCP tools and the HAR export
  keep the bytes as the device sent them (ADR-0081, ADR-0089). Not protobuf: that stays in
  the roadmap.
  Deviation: Copy gives the opened body too, beside what it shows (ADR-0199 narrows ADR-0147).
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with a gzip body, one
  over the bound and one cut; the detail's screenshot read.
- [x] **H7.R1 A gzip body that opens to nothing.** (this PR) Copy says "Nothing to copy" and leaves
  the clipboard alone, for a valid gzip of no bytes, instead of writing an empty string and saying
  "Copied". The review's finding 6.
  Accept: `./gradlew :desktop:feature:traffic:test`, a test failing without the fix.

## Outcome

### H7 milestone review

`code-review` at `medium` of `a2a9a00..f723404` (#374 to #377): a static read of the diff, 44 files,
no Gradle test run by it. Ten findings, one that normal use hits, each then read in the code here.
Findings 1, 3, 4, 5 and 7 are correctness findings left open, so this PR waits for the owner.

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | `DataDirectoryLock` keys on `toRealPath()`, so two paths to one directory pass the guard, `tryLock` throws `OverlappingFileLockException` and closing that channel drops the first store's lock. | No: `openServices` opens the store once per process, and it needs a second path across a firmlink; the review ran `toRealPath` on one, this finding not reproduced end to end | DEBT-0127 |
| 2 | Copy inflates again, and may hex dump, on the view model's thread. | No: ADR-0199 accepted it, about half a second at the bound | DEBT-0127 |
| 3 | The hex format has no bound on what it writes: 16 MiB opened is about 82 MB of text. | No: needs a binary body opening to many times its size, under the 1 MiB captured; slow, not reproduced as a hang | DEBT-0127 |
| 4 | `prettyJsonOrNull` could run out of memory on 16 MiB of `[`, an `OutOfMemoryError` it does not catch. | No: a crafted body against one's own debug tool; inferred, not run | DEBT-0127 |
| 5 | `GZIPInputStream` reads a cut or corrupt second member's header as a clean end, so the caption does not say so. | No: needs a multi-member body; the JDK behavior ran here, both cases read as a clean end | DEBT-0127 |
| 6 | A gzip body that opens to nothing copies `""` and says "Copied". | Yes: an empty answer compressed anyway, 20 bytes sent; the clipboard is overwritten | H7.R1 |
| 7 | `saysGzip` reads only the first `Content-Encoding` line, so codings on repeated lines are not several. | No: repeated lines were not met; a body over `br` stays as sent unless it starts with `1f 8b` | DEBT-0127 |
| 8 | `e2e_android.sh` restores `accelerometer_rotation` but forces `user_rotation` to 0. | No: a test script on an emulator | DEBT-0127 |
| 9 | A second gunzip beside `mockDraftOf`'s, and SDD 01 and ADR-0199 saying "Mock this" keeps the sent bytes. | No: the sentence was wrong, not the behavior. SDD 01 is corrected in this PR, ADR-0199 stays as written | DEBT-0127 |
| 10 | `WebSocketCapture`'s `remember` copies up to 32 KiB per inflated chunk on the event loop. | No: nothing measured it, and a debug proxy's sockets carry little | Rejected |

- **Shipped**: H7.1 the proxy's review findings (#374). H7.2 the rest of DEBT-0126 (#375). H7.3
  DEBT-0050 (#376). H7.4 a gzip body opens in the detail (#377). H7.R1 a gzip body that opens to
  nothing has nothing to copy (this PR).
- **Decisions**: ADR-0198, ADR-0199.
- **Debt**: Paid DEBT-0126 and DEBT-0050. Created DEBT-0127, this review's findings.
- **Deviations**: H7.2 took two KDocs its debt did not list. H7.4's Copy gives the opened body too,
  which ADR-0199 narrows from ADR-0147. H7.R1 added by the review.
- **Lessons**: ADR-0199 repeated a claim about "Mock this" no one had read in `mockDraftOf`: a list of
  what a change leaves alone needs the same reading as what it changes. The review's own check of
  finding 5 was denied, so it ran here: a finding from memory is worth one small program.
