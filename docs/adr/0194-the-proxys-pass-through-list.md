---
id: ADR-0194
title: The proxy's pass-through list, matched at each CONNECT and relayed as raw bytes
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-04
superseded_by:
---

# ADR-0194 — The proxy's pass-through list, matched at each CONNECT and relayed as raw bytes

## Context

ADR-0101 E.1 intercepts every host and adds "a pass through list in the
settings [that] tunnels those hosts untouched". M8.5 built the first
half only (DEBT-0078): a client pinning its certificates shows as a
failed `CONNECT` (SDD 11), and nothing lets that host through while the
proxy is on. What the list is for was decided there; this ADR decides
its shape. It captures less than today, never more, so it stays inside
ADR-0101's accepted privacy decision.

Forces:

- A `CONNECT` names `host:port` before any TLS byte (RFC 9110 §9.3.6),
  so the choice is made before the leaf is offered: a listed host never
  sees the proxy's certificate.
- The relay already has a raw mode, the one a `101` switches to, paced
  by each side's writability (ADR-0191, `ClientHandler`).
- Pinned SDKs often talk to several names under one domain
  (`api.`, `auth.`, `cdn.`), and their names are what the developer
  sees in the failed `CONNECT` rows.
- The proxy runs while the settings change it; restarting it would cut
  every open tunnel.

## Options

1. **Exact names and `*.domain`, read at each `CONNECT`, relayed raw,
   nothing stored** — one setting (`proxy.passThrough`, one entry per
   line); the server reads the current list from the controller at each
   `CONNECT`, so a saved list applies to the next tunnel without a
   restart. The `200` is answered once the target accepted the TCP
   connection, so a refusal is a `502` to the client, as for an
   intercepted target. A wildcard matches the names under the domain,
   not the domain itself, the way a certificate's `*.` does.
2. **As 1, and a stored `CONNECT` exchange per tunnel** — the window
   shows that the host went through; but a row with no request, no
   response and a status the proxy made up, for every connection a
   pinned SDK opens, is noise next to the failed rows it replaces.
3. **Regular expressions or ports in the entries** — more power than
   the case needs (a name copied from a failed row), and a typo that
   matches everything is easy to write.

## Decision

Option 1. Entries are compared lower case, a trailing dot ignored, an
IPv6 literal with or without brackets; the settings split what is typed
on spaces, commas and new lines and keep each entry once. "Clear all
data" writes the list back, as it does the theme and the switch.

## Consequences

- A pinned host works through the proxy once listed, and its traffic is
  not in the window: the caption under the field says so.
- An open tunnel keeps the decision it was made with; a host removed
  from the list is intercepted from its next `CONNECT`.
- Only `CONNECT` is matched: plain `http://` requests sent to the proxy
  are still recorded, which is the point of sending them there.
- `ProxyServer` takes the list as a function, so the headless server
  and tests pass none and keep today's behaviour.
