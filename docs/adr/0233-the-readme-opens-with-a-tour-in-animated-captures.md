---
id: ADR-0233
title: The README opens with a tour in animated captures, and its links are checked
status: Accepted
date: 2026-11-27
superseded_by:
---

# ADR-0233 — The README opens with a tour in animated captures, and its links are checked

## Context

ADR-0220 set the README's sections for an app developer: what App Monitor is, the debug-only
note, why, how it works, getting started, a line on MCP, links, license. Its only picture was
one static screenshot of the desktop after "How it works", from a real device.

PLAN-0022 M12.2 writes it for someone who has never seen the project: they should see what
App Monitor does before reading how it is built. M12.1 added three animated captures from
invented traffic, `docs/screenshots/demo-*.gif`, written by `./gradlew :desktop:app:demoGifs`
(SDD 02). Nothing checked that the README's relative links and images resolve; the GIFs and
the guides' anchors would be the first thing a newcomer finds broken.

Constraints: **Short setup** and **Nothing private** (AGENTS.md); ADR-0220's split between the
README and `docs/guide/` stays.

## Options

1. **A tour after the debug-only note**, one short paragraph per thing App Monitor does, with
   the GIFs; the rest of ADR-0220's README as it is. The getting started steps do not move.
2. **A GIF only at the top**, the rest unchanged. Shorter, but the mocks and the detail tabs,
   which a proxy user does not expect, stay unseen.
3. **The tour in a guide page**, linked from the README. One click away from someone deciding
   whether to try it.

## Decision

Option 1. The README opens with the tagline and `demo-traffic.gif`, the paragraph of what it
is, the debug-only note, then **A quick tour** (capture, one request's detail with
`demo-detail.gif`, mocks, breakpoints and replay with `demo-mock.gif`, the history and MCP),
**Why App Monitor**, then ADR-0220's sections from "How it works" on. The static screenshot
leaves the README; it stays in `docs/screenshots/`.

`make docs-check` runs `scripts/link_check.py`: every relative link and image in `README.md`,
`CONTRIBUTING.md` and `docs/guide/*.md` names a file that exists, and a `#anchor` a heading of
it as GitHub slugs it. External links are not fetched, so the check needs no network.

## Consequences

- A visible change to the window means running `demoGifs` again and committing the GIFs, or
  the README shows an older window. Nothing checks it.
- A renamed guide heading breaks `make docs-check` until the links follow it.
- The GIFs add about 450 KB to the repository; each one over 1.5 MB fails `demoGifs`.
