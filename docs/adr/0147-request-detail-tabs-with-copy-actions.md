---
id: ADR-0147
title: Request detail in tabs, each part copied as the device sent it
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0147 — Request detail in tabs, each part copied as the device sent it

## Context

ADR-0140 G asks for a request detail in tabs (Summary, Headers, Body,
Timing, cURL) with copy actions. PLAN-0013 H3.6b does it; the toolbar and
rows are ADR-0146, the filter sheet H3.6c.

On `dev` at `c716056`, the detail was one scrolled column: the caption
(status, duration, layer, mock rule), request and response headers and
bodies, WebSocket frames, and "Copy as cURL" beside the title, which put
`to_curl`'s command on the clipboard (ADR-0098). Nothing else could be
copied but by selecting text.

Evidence read:

- The owner's references (`AGENTS.md`, "Desktop architecture follows
  known references"): `mibo-smart-app`'s
  `core/ui/designsystem/.../component/MiboTabs.kt`: text tabs in one row,
  the chosen one strong and underlined in the primary color, each
  `selectable` with `Role.Tab` in a `selectableGroup`.
- `desktop/domain`'s `HttpExchange` holds `startedAtMs` and `endedAtMs`
  only: the protocol carries no DNS, connect, TLS or first-byte phase,
  so Timing can show the start, the end, the duration and the frame
  count, nothing finer.
- A body is stored as the device sent it (ADR-0081, ADR-0089); the
  detail renders it as JSON, text or hex on demand (ADR-0029).

## Options

1. **Tabs in `desktop/designsystem` as `MiboTabs`; the tab kept by the
   view model across picks; each copy built by the view model from the
   exchange it read, bodies as the device sent them (UTF-8, else the hex
   dump); the cURL command read when its tab opens.** One place builds
   what is copied, testable over fake repositories.
2. **Copy what the tab renders.** A body would copy as indented JSON
   when shown so, but the copy then depends on the UI's render state and
   cannot be tested without a window.
3. **Tab state in the composable.** Simpler, but it resets on every pick
   (the pane is keyed by exchange), so reading headers across requests
   needs a click per request.

## Decision

Option 1.

- `Tabs` in `desktop/designsystem/Panes.kt`, after `MiboTabs` without its
  animation or scroll: five tabs fit the detail pane at 1280 px.
- `DetailTab` (Summary, Headers, Body, Timing, cURL) is held by
  `TrafficViewModel` and stays while another request is picked. A
  connection layer exchange has Summary and Timing only (no headers,
  body or request to rebuild, ADR-0104).
- Summary: the caption as before, error, replay link, the URL with
  "Copy", connection bytes. Headers: request and response, each with
  "Copy" (`Name: value` per line). Body: request and response bodies with
  their formats and "Copy", then WebSocket frames. Timing: started and
  ended to the millisecond in this machine's zone, duration, frames, with
  "Copy". cURL: `to_curl`'s command, or why there is none, with "Copy".
- `TrafficViewModel.copy(part)` replaces `copyCurl`: the text comes from
  `textOf(exchange, part)`, a body whole as the device sent it (UTF-8
  text, else the hex dump), cURL from `ExportRepository.curl`; an empty
  part says "Nothing to copy". The note beside the title says "Copied"
  or why not, and clears on the next pick.
- "Mock this" and "Send again" stay beside the title, outside the tabs.

## Consequences

- Headers, bodies and frames are one click away instead of a scroll; app
  tests open the tab they read (`showTab`).
- A copied body is the device's text, not the indented view: pasting it
  into a request reproduces what was sent.
- Timing phases (DNS, TLS, first byte) would need the SDKs to send them:
  a protocol change, not in a plan.
