---
id: ADR-0104
title: How the desktop reads a connection exchange
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0104 — How the desktop reads a connection exchange

## Context

PLAN-0009 M8.4. ADR-0103 sends a socket the iOS connection layer saw as
`RequestStarted` (`CONNECT`, `tcp://host:port`, `capture_layer =
CONNECTION`) and, at `close`, `ResponseReceived` with status 0, no body,
`body_size` the bytes read and `sent_size` the bytes written. Until this
task every desktop reader took it as an HTTP exchange.

Evidence, from the code before this change:

- `Ingest.network` stored `status_code` as sent, so a closed connection
  had status 0: the list showed `0`, `search_requests` and `get_request`
  answered `status_code: 0`.
- `searchExchangeSizes` sums `bodies.size`; a connection has no body
  row, so benchmarks, `endpoint_stats` and `diff_runs` counted 0 bytes.
- `Endpoint.of` keeps the URL's path and drops the port; every
  connection to one host was one endpoint `/`.
- `export_har` wrote a connection as an entry with `status` 0;
  `to_curl` wrote `curl -X 'CONNECT' 'tcp://…'`, which curl cannot run.
- `response_meta` keeps the whole `ResponseReceived` without its body
  (SDD 09), `sent_size` included.

Already decided, not reopened: the event mapping (ADR-0103), the readers
sharing `desktop/storage` and `desktop/mcp` code (ADR-0097, ADR-0098).

## Options

### A. Where "no status" lives

1. **Ingest stores status 0 as null**, for every layer. No HTTP status
   is 0, so only a connection is touched, and every reader that already
   handles "no status" (filters, status classes, the MCP row) is right
   with no change.
2. **Each reader maps 0 for `CONNECTION` rows.** Every present and future
   reader must know the rule.

### B. Byte counts

1. **Decoded from `response_meta`** for `CONNECTION` rows only, in
   `searchExchangeSizes` and `ExchangeDetail.connectionBytes`. No
   migration; a decode per connection row read.
2. **Two new columns** written at ingest. A migration and a schema dump
   for a value the meta already holds.

### C. What HAR and cURL do with a connection

1. **HAR leaves it out and counts it in `log.comment`; cURL refuses it**
   with one sentence, and the window hides "Copy as cURL" on it. HAR 1.2
   describes HTTP requests and responses; an entry with no request line
   or status misleads a HAR viewer.
2. **HAR writes it as an entry with a comment.** Viewers show a
   `CONNECT` with status 0 as a failed request.

## Decision

A.1, B.1 and C.1. A connection's endpoint is `CONNECT`, its host and
`:port` as the path, so ports of one host stay apart. The window shows
`open`, `closed` or `failed` in the status column and, in the detail,
bytes sent and received instead of headers and bodies; `get_request`
returns `connection` (`sent_bytes`, `received_bytes`) and `request` and
`response` null.

## Consequences

- Benchmarks' "Sent" and "Received" mean body bytes for HTTP and socket
  bytes for a connection, the TLS overhead included.
- A connection reset after bytes moved is `failed` with no byte counts
  (ADR-0103).
- The proxy layer (M8.5) stores real HTTP and is untouched by C.
