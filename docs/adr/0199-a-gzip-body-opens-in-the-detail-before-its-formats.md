---
id: ADR-0199
title: A gzip body opens in the detail before its formats, bounded
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-04
superseded_by:
---

# ADR-0199 — A gzip body opens in the detail before its formats, bounded

## Context

PLAN-0017 H7.4: a body whose `Content-Encoding` is `gzip` reads as hex in the
detail (owner, field test, Firebase's `batchlog`). The store, the MCP tools and
the HAR export keep the bytes as the device sent them (ADR-0081, ADR-0089).

Evidence read:

- The header alone does not prove the bytes are gzip: ADR-0114 records an
  OkHttp body that is not gzip under a `gzip` header, and `sdk/ktor` capturing a
  response still encoded (SDD 08). The mock editor already checks the magic
  bytes `1f 8b` (`mockDraftOf`).
- A stored body is at most `Protocol.MAX_BODY_BYTES` (1 MiB). In
  `GzipBodyTest` 32 MiB of zeros compress far below it, so what a body opens to
  has to be bounded by itself, not by the protocol.
- A capture cut at that limit is an everyday gzip stream cut in the middle, and
  a cut can fall inside a UTF-8 character, which `utf8OrNull` refuses whole.
- `java.util.zip.GZIPInputStream` reads several members as one and hands over
  what it inflated before an `EOFException` or a `ZipException`, apart from the
  read that failed (`GzipBodyTest`).
- `GzipBodyTest`, on this machine: 16 MiB of random bytes open and render as hex
  in 0.5 s off the UI thread; the 32 MiB bomb stops at the bound in 0.1 s.

## Options

1. **Open in `DecodedBody`, off the UI thread, up to 16 MiB, when the header is
   a lone `gzip` or `x-gzip` and the bytes start with `1f 8b`; a caption says what
   happened.** The formats, the default format and the search keep working on
   what is shown.
2. **A fourth chip, "Gzip".** The sent bytes stay one chip away, but JSON still
   would not open by itself, and each format would need its opened twin.
3. **Open on ingest.** Faster to read, but the store would no longer hold what
   the device sent (ADR-0081).

## Decision

Option 1, `GzipBody.kt` in `desktop/feature/traffic`.

- Bound: `MAX_OPENED_BYTES`, 16 MiB, the size `MAX_RENDERED_CHARS` already holds
  a body to, and 16 times a body cut at the protocol's limit.
- Only one coding: under `br, gzip` the bytes stay as sent; so do bytes not
  starting as gzip, silently, since they are what the app read.
- A partial body (cut, broken, over the bound) leaves out of its text a last
  character the cut left incomplete, so it does not fall back to hex. Its caption
  gives the bytes read and the bytes sent. A stream nothing can be read from shows
  the bytes as sent, and says so.
- Copy gives the opened body, UTF-8 else its hex dump: this narrows ADR-0147's
  "as the device sent it" for a gzip body only, whose compressed hex nobody can
  read beside the JSON above it. `to_curl`, the MCP tools, the HAR export, "Mock
  this" and a replay keep the sent bytes.

## Consequences

- The compressed bytes no longer show in the window; a tool that needs them has
  the MCP tools and the HAR export.
- Copying runs on the view model's thread as before: at the bound, a hex dump
  of 16 MiB is about half a second there, only for an opened binary that large.
- A WebSocket frame has no headers here, so a frame is never opened.
- `deflate`, `br` and protobuf stay unread; protobuf is in the roadmap.
