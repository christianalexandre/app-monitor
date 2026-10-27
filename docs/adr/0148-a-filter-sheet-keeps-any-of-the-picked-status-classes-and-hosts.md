---
id: ADR-0148
title: A filter sheet keeps any of the picked status classes and hosts
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0148 — A filter sheet keeps any of the picked status classes and hosts

## Context

ADR-0140 G asks for a filter sheet by status class and host, as
`app-network-monitor`'s client has; ADR-0146 left the method, status and
"Failed only" choices in the toolbar until this sheet (PLAN-0013 H3.6c).
`ExchangeFilter` held one optional `StatusClass` and no host.

Evidence read:

- `christianalexandre/homebrew-app-network-monitor-client`,
  `Presentation/Features/Dashboard/DashboardView.swift` and
  `DashboardViewModel.swift`: a `.sheet` titled "Filters" with a status
  section (one toggle per `StatusCodeCategory`, colored by category, "All"
  and "None") and a host section (one toggle per host of the logs,
  `availableHosts` sorted, "All" and "None"); the view model keeps the
  *disabled* categories and hosts and drops a log in either set.
- `exchanges` already has `exchanges_host` (`Exchange.sq`), so a host
  `IN (…)` clause is answered through an index (`SearchTest`'s plan
  check).
- The MCP tools take one `status_class` (SDD 10); their schema is not
  changed by this task.

## Options

1. **Sets of kept classes and hosts, empty keeps all.** `ExchangeFilter`
   gets `statusClasses` and `hosts`; a row matches any one of each set.
   Hosts come from a distinct read of the shown list (live, run or
   session). A host that arrives after a host is picked is not shown,
   which is what "keeps only that host's rows" asks.
2. **Sets of hidden classes and hosts, as the reference.** Every new host
   shows at once; but "None" hides everything, and the store would need
   `NOT IN` clauses that cannot use `exchanges_host`.
3. **One optional host beside the one status class.** Smallest change, but
   two hosts or 4xx and 5xx together cannot be kept.

## Decision

Option 1.

- **Filter**: `statusClasses: Set<StatusClass>` replaces `statusClass`,
  `hosts: Set<String>` is added, in `desktop/domain` and
  `desktop/storage`; the MCP's `status_class` maps to a set of one.
- **Store**: one `OR` of the classes' ranges, `exchanges.host IN (…)`;
  the live list matches the same. `observeHosts(runId, sessionId)` reads
  the distinct hosts of a session, a run or every stored exchange, and of
  the live list, sorted.
- **Sheet** (`FilterSheet`, a `DialogCard`): method, one `ToggleChip` per
  status class in its badge tone (ADR-0146), "Failed only", one checkbox
  per host with "All", "Reset" and "Done". A host picked and since gone
  from the list stays listed until it is unpicked. The toolbar keeps the
  search and a "Filters" button with the count of set filters.
- **`ToggleChip`** in `desktop/designsystem`: a `Badge` that toggles,
  its tone's color with a border while on, neutral while off.

## Consequences

- The sheet's choices are not saved across launches, as the toolbar's
  were not.
- `endpoint_stats`, the benchmarks and the HAR export take the same
  filter, so they keep several classes or hosts too when a caller sets
  them; no caller does yet besides the window's list.
- SDD 01, 09 and 10 say what the filter holds and where the sheet is.
