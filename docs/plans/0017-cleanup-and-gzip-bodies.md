---
id: PLAN-0017
title: The sweep's last findings and gzip bodies
status: Active # Draft | Active | Done | Abandoned
milestone: H7
started: 2026-11-04
finished:
---

# PLAN-0017 — The sweep's last findings and gzip bodies (H7)

## Goal

Before the owner's release to `main` and field test, DEBT-0126 and DEBT-0050 are paid or
dropped with their reasons, and a gzip body opens in the window. The owner asked for this
plan on 2026-11-04, and picked gzip alone from the roadmap's *Later — readable bodies*.
The roadmap's `Hold` stays: M10 is still the owner's go.

## Tasks

- [ ] **H7.1 The proxy's review findings.** A WebSocket message ending in a `BFINAL=1` block
  no longer leaves every later message captured empty (DEBT-0126, item 2, RFC 7692 7.2.3.4);
  a leaf that cannot be built in `TunnelTls`' asynchronous callback fails the tunnel instead
  of leaving it hanging (item 1); a plain tunnel closed without a byte is not labelled a TLS
  failure (item 3).
  Accept: `./gradlew :desktop:proxy:test`, a test for each item failing without its fix.
- [ ] **H7.2 The rest of DEBT-0126.** The data directory lock survives a refused second open
  inside one JVM (item 4); `scripts/e2e_android.sh`'s `grep -q` under `pipefail`, and
  `accelerometer_rotation` restored on the emulator (item 5); the 16 KDocs over guideline 02's
  4 lines (item 6). DEBT-0126 paid.
  Accept: `./gradlew :desktop:storage:test`; `make check`; `make e2e-android`.
- [ ] **H7.3 DEBT-0050.** Its two `pr-conventions.yml` items read against today's
  `scripts/trailer_check.sh` and `origin/main..origin/dev`, each paid or dropped with that
  evidence; `scripts/debt_check.sh`'s basename and short path items paid or dropped with a
  case from this repository.
  Accept: `make scripts-test`; `make debt-check`.
- [ ] **H7.4 Gzip bodies open in the window.** A request or response body whose
  `Content-Encoding` is `gzip` is opened before the detail's Text, JSON and Hex formats, with
  a bound on what it inflates to, and a cut or broken stream shows what could be read and
  says so. Only what the window shows changes: the store, the MCP tools and the HAR export
  keep the bytes as the device sent them (ADR-0081, ADR-0089). Not protobuf: that stays in
  the roadmap.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test`, with a gzip body, one
  over the bound and one cut; the detail's screenshot read.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
